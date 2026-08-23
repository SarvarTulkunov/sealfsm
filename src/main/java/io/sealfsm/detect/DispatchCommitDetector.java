package io.sealfsm.detect;

import io.sealfsm.model.CommitForm;
import spoon.reflect.CtModel;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtTypePattern;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recognises <em>centralized dispatch</em> by the discrimination itself rather
 * than by the signature of the method hosting it.
 *
 * <p>The original recognizer accepted exactly one shape: a method that both takes
 * and returns the hierarchy type H, containing {@code return switch (state) {...}}.
 * That is one idiom among several equally common ones. All of the following are
 * the same automaton, dispatched the same way, and differ only in where the
 * switch is hosted and how its result is installed:
 *
 * <pre>{@code
 *   static H next(H state, Event e) { return switch (state) { ... }; }   // value return
 *   H handle(Event e) { this.state = switch (this.state) { ... }; ... }  // field, explicit this
 *   H handle(Event e) { state = switch (state) { ... }; return state; }  // field, bare
 *   H handle(Event e) { H next = switch (state) { ... }; ... }           // local accumulator
 * }</pre>
 *
 * <p>So this detector scans <em>every</em> switch in the model, asks whether its
 * selector's type is in H, and then asks what happens to its result. If the
 * result is committed as an H value — returned as H, or written to an H-typed
 * field or local — the switch is a transition producer, and the commit mechanism
 * is recorded as a {@link CommitForm} for stratified reporting.
 *
 * <h2>The switch is one spelling of the discrimination, not the discrimination</h2>
 * Keying on {@code CtSwitch} confused a syntactic construct with the semantic
 * notion. What makes a dispatch a dispatch is that the selector's <em>dynamic
 * type</em> is discriminated across H and each branch commits a successor; a
 * {@code switch} over a sealed type and a chain of {@code instanceof} tests over
 * the same value are two spellings of exactly that, the way {@code return switch}
 * and {@code this.f = switch} are two spellings of one commit.
 *
 * <pre>{@code
 *   H step(Event e) {                       // no H-typed parameter: the signature
 *       if (state instanceof Initial) {     // recognizer never saw this method,
 *           ...                             // and there is no switch for the
 *       } else if (state instanceof Closed) {  // switch recognizer to find.
 *           ...
 *       }
 *   }
 * }</pre>
 *
 * <p>That shape is the dominant one in code predating pattern-matching switch,
 * which is most Java, so its absence was a recall hole rather than a scope line.
 * A chain is admitted on exactly the terms a switch is: the selector's type must
 * be in H, the branches must commit an H value, and no branch may nest one H
 * value inside another. Nothing about the commit requirement is relaxed for it —
 * {@code if (s instanceof Circle) return "circle";} is the {@code instanceof}
 * spelling of the exhaustive fold and is rejected by the same codomain test that
 * rejects {@code label = switch (state)}.
 *
 * <p>Two further requirements are specific to the chain form, both because an
 * {@code if} is so much weaker a signal than a {@code switch}:
 * <ul>
 *   <li>the chain must attribute branches to at least <b>two</b> distinct
 *       from-states (two type tests, or one plus an {@code else}). One type test
 *       is a <em>check</em>; discriminating between states is what makes it a
 *       dispatch. This mirrors {@link CarrierTransitionDetector#qualifies}, which
 *       requires carrier methods on two permitted subtypes for the same reason;</li>
 *   <li>every link must test the <b>same</b> selector variable, so a method that
 *       happens to type-test two unrelated H values is not read as one dispatch.</li>
 * </ul>
 *
 * <h2>Why the commit check is the precision guard</h2>
 * Widening a recognizer is where false positives enter, and the obvious false
 * positive here is the <em>exhaustive fold</em>: a switch over a sealed type whose
 * arms produce a {@code String}, an {@code int}, or a log line. Those are
 * pattern matching over a sum type, not transitions, and they are structurally
 * indistinguishable from a transition switch <em>at the switch</em> — the only
 * thing that separates them is the codomain. Requiring an H-typed commit is
 * therefore not a convenience test bolted on afterwards; it is the whole
 * discriminator, and it is why {@code name = switch (state) { ... }} yielding
 * strings is rejected while {@code state = switch (state) { ... }} is accepted.
 *
 * <p>A second guard rejects switches whose arms <em>nest</em> H values inside
 * other H values, which is composition (a tree rewrite), not succession. That
 * predicate is owned by {@link CarrierTransitionDetector#nestsHierarchyValue}
 * and shared, so the widened path cannot disagree with the carrier path about
 * what counts as a recursive data type.
 */
public final class DispatchCommitDetector {

    private DispatchCommitDetector() {
    }

    /**
     * One recognised transition producer.
     *
     * @param host     the method the dispatch lives in, used to enumerate Σ from
     *                 its event parameter
     * @param dispatch the discrimination over H itself — a {@link CtAbstractSwitch}
     *                 or the head {@link CtIf} of an {@code instanceof} chain. The
     *                 extractor walks exactly this node, never the whole host
     *                 body, so unrelated statements around it (a trailing
     *                 {@code return this.state;}) cannot turn into spurious edges
     * @param commit   how the discrimination's result becomes the machine's state
     * @param selector the variable being discriminated, for a chain; {@code null}
     *                 for a switch, whose selector the extractor reads off the
     *                 node itself. A chain has no single node carrying it — it is
     *                 spread across the links — and re-deriving it in the
     *                 extractor is how the two would come to disagree about which
     *                 value is the state
     */
    public record Producer(CtMethod<?> host, CtElement dispatch, CommitForm commit,
                           CtVariable<?> selector) {
        /** A switch producer: the node carries its own selector. */
        static Producer ofSwitch(CtMethod<?> host, CtAbstractSwitch<?> dispatch, CommitForm commit) {
            return new Producer(host, dispatch, commit, null);
        }
    }

    /**
     * One link of an {@code instanceof} chain: {@code else if (sel instanceof T &&
     * extra) branch}. The extra conjuncts are kept separate from the type test
     * rather than folded into one condition string, because they are not the same
     * kind of thing: the type test <em>selects the from-state</em> and must not
     * also appear as a data guard, while the rest is an ordinary condition that
     * may still split into an event label and a guard.
     */
    public record ChainLink(CtTypeReference<?> type, List<CtExpression<?>> extra, CtStatement branch) {
    }

    /**
     * A decomposed {@code instanceof} chain over one hierarchy-typed selector.
     *
     * @param selector  the discriminated variable — a parameter, a local, or a
     *                  field with or without {@code this}
     * @param links     the type-tested branches, in source order (order matters:
     *                  a later link is reached only when every earlier test failed)
     * @param otherwise the final {@code else}, or {@code null}. Only an
     *                  <em>explicit</em> else is a branch of the chain; statements
     *                  <em>after</em> the chain are the host's plumbing and are
     *                  not walked as part of it
     */
    public record TypeChain(CtVariable<?> selector, List<ChainLink> links, CtStatement otherwise) {
        /** How many distinct from-states this chain attributes a branch to. */
        int discriminatedBranches() {
            return links.size() + (otherwise == null ? 0 : 1);
        }
    }

    /**
     * Every transition producer for {@code root} in the model, in source order.
     * Both {@code switch} statements and {@code switch} expressions are scanned:
     * the expression form covers all four idioms above, and the statement form is
     * reached when its arms individually commit (handled downstream by the walker).
     * {@code instanceof} chains are scanned last, so a hierarchy dispatched by a
     * switch keeps the switch as its producer and the widening can only add
     * machines, never re-attribute one.
     */
    public static List<Producer> find(CtType<?> root, CtModel model) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();
        List<Producer> out = new ArrayList<>();
        for (CtSwitchExpression<?, ?> sw : model.getElements(new TypeFilter<>(CtSwitchExpression.class))) {
            addIfProducer(sw, hierarchy, out);
        }
        for (CtSwitch<?> sw : model.getElements(new TypeFilter<>(CtSwitch.class))) {
            addIfProducer(sw, hierarchy, out);
        }
        for (CtIf ctIf : model.getElements(new TypeFilter<>(CtIf.class))) {
            addChainProducer(ctIf, hierarchy, rootQn, out);
        }
        return out;
    }

    private static void addIfProducer(CtAbstractSwitch<?> sw, Set<String> hierarchy, List<Producer> out) {
        if (!dispatchesOnHierarchy(sw, hierarchy)) return;
        CommitForm commit = commitFormOf(sw, hierarchy);
        if (commit == null) return;                       // foreign codomain — an exhaustive fold
        if (nestsHierarchyValue(sw, hierarchy)) return;   // composition, not succession
        CtMethod<?> host = enclosingMethod(sw);
        if (host == null) return;                         // an initializer, not a transition function
        out.add(Producer.ofSwitch(host, sw, commit));
    }

    /**
     * The {@code instanceof}-chain analogue of {@link #addIfProducer}, admitted on
     * the same terms: an H-typed selector, an H-typed commit, and no composition.
     * The two extra tests are the chain-specific ones motivated in the class
     * javadoc — at least two discriminated branches, and one selector throughout,
     * both enforced inside {@link #chainOf}.
     */
    private static void addChainProducer(CtIf head, Set<String> hierarchy, String rootQn,
                                         List<Producer> out) {
        if (isChainContinuation(head, hierarchy, rootQn)) return;  // a link, not the head
        if (insideFunctionalCallable(head)) return;                // F7 already owns this body
        TypeChain chain = chainOf(head, hierarchy, rootQn);
        if (chain == null) return;
        CtMethod<?> host = enclosingMethod(head);
        if (host == null) return;                         // an initializer, not a transition function
        CommitForm commit = commitFormOfChain(chain, host, hierarchy);
        if (commit == null) return;                       // foreign codomain — an exhaustive fold
        if (chainNestsHierarchyValue(chain, hierarchy)) return;    // composition, not succession
        out.add(new Producer(host, head, commit, chain.selector()));
    }

    /**
     * Is this {@code if} the {@code else} of an enclosing type-test chain? Such an
     * {@code if} is a <em>link</em>, and the chain that owns it walks it; emitting
     * a producer for it as well would report the tail of one dispatch as a second
     * dispatch, and the extractor would walk the same branches twice.
     */
    private static boolean isChainContinuation(CtIf ctIf, Set<String> hierarchy, String rootQn) {
        CtElement parent = parentOf(ctIf);
        if (parent instanceof CtBlock<?> b && b.getStatements().size() == 1) parent = parentOf(b);
        if (!(parent instanceof CtIf outer)) return false;
        if (unwrapSingle(outer.getElseStatement()) != ctIf) return false;
        return typeTestOf(outer.getCondition(), hierarchy, rootQn) != null;
    }

    /**
     * Is this chain inside a lambda or an anonymous class? Those bodies are
     * discovered and walked as functional transition callables (F7), which
     * attributes them a selector and an enclosing-method event label of their own.
     * Excluding them here is the same ownership rule
     * {@link StateMachineClassifier#findCentralizedTransitionMethods} already
     * applies to anonymous-class methods, and for the same reason: one body, one
     * walker.
     */
    private static boolean insideFunctionalCallable(CtElement e) {
        try {
            if (e.getParent(CtLambda.class) != null) return true;
            CtType<?> owner = e.getParent(CtType.class);
            return owner instanceof CtClass<?> c && c.isAnonymous();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Decompose {@code if (sel instanceof A) ... else if (sel instanceof B) ...}
     * into its links, or answer {@code null} when this is not a state
     * discrimination. Every link must test the same selector: a method that
     * type-tests two different H values is doing two things, and reading it as one
     * chain would attribute one value's branches to the other's states.
     */
    public static TypeChain chainOf(CtIf head, Set<String> hierarchy, String rootQn) {
        TypeTest first = typeTestOf(head.getCondition(), hierarchy, rootQn);
        if (first == null) return null;
        CtVariable<?> selector = declarationOf(first.selector());
        if (selector == null) return null;

        List<ChainLink> links = new ArrayList<>();
        CtStatement otherwise = null;
        CtIf current = head;
        Set<CtIf> seen = new LinkedHashSet<>();
        while (current != null && seen.add(current)) {
            TypeTest test = typeTestOf(current.getCondition(), hierarchy, rootQn);
            if (test == null || !sameVariable(test.selector(), first.selector())) {
                // An ordinary conditional in the middle of the chain: it and
                // everything below it are the chain's fall-through branch, not a
                // state-attributed link.
                otherwise = current;
                break;
            }
            links.add(new ChainLink(test.type(), test.extra(), current.getThenStatement()));
            CtStatement next = unwrapSingle(current.getElseStatement());
            if (next instanceof CtIf nested) {
                current = nested;
            } else {
                otherwise = current.getElseStatement();
                current = null;
            }
        }
        TypeChain chain = new TypeChain(selector, links, otherwise);
        return chain.discriminatedBranches() >= 2 ? chain : null;
    }

    /** A type test on a hierarchy value, with the conjuncts that accompany it. */
    public record TypeTest(CtVariableReference<?> selector, CtTypeReference<?> type,
                           List<CtExpression<?>> extra) {
    }

    /**
     * Read {@code sel instanceof T} — alone, or as one conjunct of an {@code &&} —
     * where {@code sel} is a variable whose declared type is in H and {@code T} is
     * a permitted subtype. The conjunction is flattened first, so the position of
     * the type test within it does not matter.
     *
     * <p>Only {@code &&} is flattened. Under {@code ||} the branch is reachable
     * with the test <em>false</em>, so the from-state is not determined and the
     * condition stays an opaque guard — the same line
     * {@code TransitionExtractor.splitEventCondition} draws for event tests. A
     * negated test ({@code !(state instanceof Init)}) likewise attributes nothing:
     * it names every state but one.
     */
    public static TypeTest typeTestOf(CtExpression<?> cond, Set<String> hierarchy, String rootQn) {
        List<CtExpression<?>> conjuncts = new ArrayList<>();
        flattenConjunction(cond, conjuncts);
        for (int i = 0; i < conjuncts.size(); i++) {
            CtExpression<?> c = conjuncts.get(i);
            if (!(c instanceof CtBinaryOperator<?> bin)
                    || bin.getKind() != BinaryOperatorKind.INSTANCEOF) {
                continue;
            }
            if (!(bin.getLeftHandOperand() instanceof CtVariableAccess<?> va)
                    || va.getVariable() == null) {
                continue;
            }
            CtTypeReference<?> selType = va.getVariable().getType();
            if (selType == null || !hierarchy.contains(selType.getQualifiedName())) continue;
            CtTypeReference<?> tested = instanceofType(bin.getRightHandOperand());
            if (tested == null || !hierarchy.contains(tested.getQualifiedName())) continue;
            if (tested.getQualifiedName().equals(rootQn)) continue;  // selects nothing
            List<CtExpression<?>> extra = new ArrayList<>(conjuncts);
            extra.remove(i);
            return new TypeTest(va.getVariable(), tested, extra);
        }
        return null;
    }

    private static void flattenConjunction(CtExpression<?> cond, List<CtExpression<?>> out) {
        if (cond == null) return;
        if (cond instanceof CtBinaryOperator<?> bin && bin.getKind() == BinaryOperatorKind.AND) {
            flattenConjunction(bin.getLeftHandOperand(), out);
            flattenConjunction(bin.getRightHandOperand(), out);
            return;
        }
        out.add(cond);
    }

    /** The type an {@code instanceof} tests for, bound ({@code x instanceof T t}) or not. */
    private static CtTypeReference<?> instanceofType(CtExpression<?> rhs) {
        if (rhs == null) return null;
        if (rhs instanceof CtTypeAccess<?> ta) return ta.getAccessedType();
        if (rhs instanceof CtTypePattern tp && tp.getVariable() != null) {
            return tp.getVariable().getType();
        }
        return rhs.getType();
    }

    /**
     * Same variable, decided by declaration identity with the simple name as a
     * prefilter and as the fallback when Spoon cannot bind the read. Identity is
     * what the question asks — a field {@code state} and a parameter {@code state}
     * in one method are two values — and the name is only consulted where identity
     * is unavailable, which is the direction that costs a recognition rather than
     * inventing one.
     */
    private static boolean sameVariable(CtVariableReference<?> a, CtVariableReference<?> b) {
        if (a == null || b == null) return false;
        if (!a.getSimpleName().equals(b.getSimpleName())) return false;
        CtVariable<?> da = declarationOf(a);
        CtVariable<?> db = declarationOf(b);
        return da == null || db == null || da == db;
    }

    private static CtVariable<?> declarationOf(CtVariableReference<?> ref) {
        try {
            return ref == null ? null : ref.getDeclaration();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Unwrap a block holding one statement, so {@code else { if (..) }} reads as {@code else if}. */
    private static CtStatement unwrapSingle(CtStatement st) {
        if (st instanceof CtBlock<?> b && b.getStatements().size() == 1) {
            return b.getStatements().get(0);
        }
        return st;
    }

    /**
     * What happens to the chain's result, or {@code null} when nothing in it
     * commits an H value. This is the <em>same</em> discriminator the switch path
     * uses, applied branch by branch because a chain has no single result
     * expression: {@code return} counts only when the host returns H, and an
     * assignment counts only when its target is declared H.
     *
     * <p>Scanning is confined to the chain's own branches. A commit elsewhere in
     * the host body says nothing about whether <em>this</em> discrimination
     * installs a successor, and claiming it would be the inter-procedural mistake
     * one scope smaller.
     */
    private static CommitForm commitFormOfChain(TypeChain chain, CtMethod<?> host,
                                                Set<String> hierarchy) {
        CtTypeReference<?> ret = host.getType();
        boolean hostReturnsHierarchy = ret != null && hierarchy.contains(ret.getQualifiedName());
        CommitForm found = null;
        for (CtStatement branch : chainBranches(chain)) {
            if (hostReturnsHierarchy) {
                for (CtReturn<?> r : branch.getElements(new TypeFilter<>(CtReturn.class))) {
                    if (r.getReturnedExpression() != null) return CommitForm.VALUE_RETURN;
                }
            }
            for (CtAssignment<?, ?> asg : branch.getElements(new TypeFilter<>(CtAssignment.class))) {
                CommitForm c = commitOfTarget(asg.getAssigned(), hierarchy);
                if (c != null && found == null) found = c;
            }
        }
        return found;
    }

    /** Every statement the chain owns: each link's branch, plus the final else. */
    private static List<CtStatement> chainBranches(TypeChain chain) {
        List<CtStatement> out = new ArrayList<>();
        for (ChainLink l : chain.links()) {
            if (l.branch() != null) out.add(l.branch());
        }
        if (chain.otherwise() != null) out.add(chain.otherwise());
        return out;
    }

    /**
     * The chain form of the sibling-vs-nested guard. Shares
     * {@link CarrierTransitionDetector#nestsHierarchyValue} with the switch and
     * carrier paths, so a tree rewrite written with {@code instanceof} is vetoed
     * by exactly the predicate that vetoes it written with {@code switch}.
     */
    private static boolean chainNestsHierarchyValue(TypeChain chain, Set<String> hierarchy) {
        for (CtStatement branch : chainBranches(chain)) {
            for (CtReturn<?> r : branch.getElements(new TypeFilter<>(CtReturn.class))) {
                if (producesNested(r.getReturnedExpression(), hierarchy)) return true;
            }
            for (CtAssignment<?, ?> asg : branch.getElements(new TypeFilter<>(CtAssignment.class))) {
                if (commitOfTarget(asg.getAssigned(), hierarchy) != null
                        && producesNested(asg.getAssignment(), hierarchy)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Is the switch selector's compile-time type inside H? The selector may be a
     * parameter, a local, {@code this.field} or a bare field read; Spoon resolves
     * all of them to the same {@link CtTypeReference}, so no special-casing per
     * selector form is needed — only the guarded access, since an unresolvable
     * type under {@code noClasspath} must read as "not a dispatch" rather than
     * throw.
     */
    private static boolean dispatchesOnHierarchy(CtAbstractSwitch<?> sw, Set<String> hierarchy) {
        try {
            CtExpression<?> selector = sw.getSelector();
            CtTypeReference<?> t = selector == null ? null : selector.getType();
            return t != null && hierarchy.contains(t.getQualifiedName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * What happens to the switch's result, or {@code null} when it is not
     * committed as an H value at all.
     *
     * <p>Only the immediate syntactic context is consulted. Following the value
     * further — through a helper, through another object's field — would cross
     * the intra-procedural boundary, and a commit we cannot see in this method is
     * a commit we must not claim.
     */
    private static CommitForm commitFormOf(CtAbstractSwitch<?> sw, Set<String> hierarchy) {
        CtElement parent = parentOf(sw);
        if (parent == null) return null;

        // (a) return switch (sel) { ... };  — H only if the METHOD returns H, which
        // is what rejects `String describe() { return switch (state) {...}; }`.
        if (parent instanceof CtReturn<?> || parent instanceof CtYieldStatement) {
            CtMethod<?> host = enclosingMethod(sw);
            CtTypeReference<?> ret = host == null ? null : host.getType();
            return ret != null && hierarchy.contains(ret.getQualifiedName())
                    ? CommitForm.VALUE_RETURN : null;
        }

        // (b)/(c) this.field = switch (sel) { ... };  /  field = switch (sel) { ... };
        // (d) local = switch (sel) { ... };
        if (parent instanceof CtAssignment<?, ?> asg) {
            return commitOfTarget(asg.getAssigned(), hierarchy);
        }

        // (d) H next = switch (sel) { ... };  — declaration-site accumulator.
        if (parent instanceof CtLocalVariable<?> lv) {
            CtTypeReference<?> t = lv.getType();
            return t != null && hierarchy.contains(t.getQualifiedName())
                    ? CommitForm.LOCAL_ACCUMULATOR : null;
        }
        return null;
    }

    /**
     * Is writing to {@code target} how a successor gets installed? The extractor
     * asks this when walking a chain recognised as committing by mutation: a
     * chain has no single result expression, so its commit is an assignment
     * inside a branch, and that assignment is the produced value. Asking the
     * detector rather than re-deriving the test is what keeps "what counts as a
     * commit" a single definition.
     */
    public static boolean isCommitTarget(CtExpression<?> target, Set<String> hierarchy) {
        return commitOfTarget(target, hierarchy) != null;
    }

    /**
     * The commit form implied by an assignment target: an H-typed field is a field
     * mutation, an H-typed local is an accumulator, anything else is not a commit.
     * The declared TYPE decides, never the name — two machines in one model
     * routinely both call their field {@code state}.
     */
    private static CommitForm commitOfTarget(CtExpression<?> target, Set<String> hierarchy) {
        if (!(target instanceof CtVariableAccess<?> va) || va.getVariable() == null) return null;
        CtVariableReference<?> vref = va.getVariable();
        CtTypeReference<?> declared = vref.getType();
        if (declared == null || !hierarchy.contains(declared.getQualifiedName())) return null;
        return vref.getDeclaration() instanceof CtLocalVariable<?>
                ? CommitForm.LOCAL_ACCUMULATOR : CommitForm.FIELD_MUTATION;
    }

    /**
     * Does any arm produce a hierarchy value <em>nested inside</em> another one?
     * {@code case Add(var l, var r) -> new Add(simplify(l), simplify(r))} builds a
     * bigger H out of smaller ones — a tree rewrite whose "next" is a child, not a
     * successor. Only the arms' produced values are inspected, one level deep,
     * matching the carrier detector's bound exactly.
     */
    private static boolean nestsHierarchyValue(CtAbstractSwitch<?> sw, Set<String> hierarchy) {
        for (CtCase<?> c : sw.getCases()) {
            for (CtStatement st : c.getStatements()) {
                if (producesNested(armValue(st), hierarchy)) return true;
            }
        }
        return false;
    }

    /** The value an arm produces: an arrow expression, or a {@code yield}/{@code return}. */
    private static CtExpression<?> armValue(CtStatement st) {
        if (st instanceof CtYieldStatement ys) return ys.getExpression();
        if (st instanceof CtReturn<?> r) return r.getReturnedExpression();
        return st instanceof CtExpression<?> e ? e : null;
    }

    private static boolean producesNested(CtExpression<?> value, Set<String> hierarchy) {
        if (value == null) return false;
        if (value instanceof CtConditional<?> cond) {
            return producesNested(cond.getThenExpression(), hierarchy)
                    || producesNested(cond.getElseExpression(), hierarchy);
        }
        return CarrierTransitionDetector.nestsHierarchyValue(value, hierarchy);
    }

    /**
     * Is {@code e} a hierarchy value? Mirrors the carrier detector's notion so the
     * two recognizers cannot disagree about what an H value is.
     */
    static boolean isHierarchyValue(CtExpression<?> e, Set<String> hierarchy) {
        if (e == null) return false;
        if (e instanceof CtThisAccess<?>) return true;
        if (e instanceof CtConstructorCall<?> cc) {
            CtTypeReference<?> t = cc.getType();
            return t != null && hierarchy.contains(t.getQualifiedName());
        }
        CtTypeReference<?> t = e.getType();
        return t != null && hierarchy.contains(t.getQualifiedName());
    }

    private static CtElement parentOf(CtElement e) {
        try {
            return e.isParentInitialized() ? e.getParent() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static CtMethod<?> enclosingMethod(CtElement e) {
        try {
            return e.getParent(CtMethod.class);
        } catch (Throwable t) {
            return null;
        }
    }
}
