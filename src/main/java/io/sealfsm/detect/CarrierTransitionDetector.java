package io.sealfsm.detect;

import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtTargetedExpression;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Recognises the <em>polymorphic carrier</em> encoding (finding F8): the GoF
 * State pattern in which each permitted subtype owns its transition logic in an
 * overridden method that returns a <em>carrier</em> object wrapping the next
 * state, rather than returning the hierarchy type H itself.
 *
 * <pre>{@code
 *   public final class CloseWait implements TcpState {
 *       @Override public Transition on(Event event) {
 *           if (event instanceof SegmentArrival seg && seg.rst())
 *               return Transition.to(new Closed(), Action.SIGNAL_ABORT);
 *           if (event == UserCall.CLOSE)
 *               return Transition.to(new LastAck(), Action.SND_FIN);
 *           return Transition.ignore(this);
 *       }
 *   }
 * }</pre>
 *
 * <p>{@link StateMachineClassifier} qualifies a hierarchy only when some method
 * <em>returns</em> H, so the shape above reads as a plain sum type and the whole
 * machine is lost. Widening the recognizer to accept "successor appears as an
 * argument to a shallow carrier" is what this class does — and widening a
 * recognizer is exactly where false positives enter, so the widening is paired
 * with an explicit precision guard.
 *
 * <h2>The sibling-vs-nested predicate</h2>
 * A sealed hierarchy whose members build other members of the same hierarchy is
 * not automatically an automaton: recursive tree builders, exhaustive folds and
 * tag-dispatched deserializers all do it. What separates them is <em>how</em> the
 * constructed H-value is used:
 *
 * <ul>
 *   <li><b>peer</b> (state machine): the H-value is a terminal result — returned
 *       bare, handed straight to a shallow carrier, or {@code this}. It replaces
 *       the current state with a sibling of it.</li>
 *   <li><b>nested</b> (not a state machine): the H-value is a child argument in
 *       the construction of another H node. It <em>composes</em> H rather than
 *       succeeding it — tree building.</li>
 * </ul>
 *
 * The rule of thumb is "produces a peer state ⇒ FSM; nests H inside a bigger H
 * ⇒ not FSM", and {@link #shapeOf} is its executable form.
 *
 * <h2>F20 — a state may REMEMBER its predecessor, and the veto has a bound</h2>
 * Two narrowings keep that rule from swallowing ordinary automata. Both are about
 * the same confusion: an H value appearing in a constructor argument list is not
 * by itself composition.
 *
 * <ul>
 *   <li><b>Carrying the current state is not nesting.</b> {@code new Retrying(this,
 *       attempts + 1)} stores the state it succeeds — a predecessor pointer, the
 *       ordinary shape of a retry/backoff protocol whose failure state has to know
 *       what it is retrying. Structural recursion never does that: a fold
 *       <em>descends into</em> its argument's parts, it does not wrap the argument
 *       whole. So an argument that <em>is</em> the current state (bare {@code this},
 *       the dispatch selector, the type-pattern binding that matched the from-state)
 *       is skipped by {@link #nestsHierarchyValue}. Nothing else is skipped: a
 *       <em>part</em> of the current state ({@code this.left}, {@code n.operand()})
 *       is exactly what a tree rewrite passes, and still nests.</li>
 *   <li><b>The hierarchy-wide veto needs hierarchy-wide evidence.</b> One nested
 *       production is a fact about one expression. It vetoes the type only when it
 *       is <em>self-composing</em> — a surviving nested argument that is derived
 *       from the current state, which is structural recursion and sufficient on its
 *       own ({@code new Neg(operand.simplify().result())}, a single-member tree).
 *       Any other nested production is mere evidence, and {@link #composesItself}
 *       requires two of them across two distinct members before rejecting the whole
 *       type. A lone one is downgraded by the extractor to a per-edge UNRESOLVED
 *       transition carrying a note — recorded, never a verdict about the type and
 *       never silently dropped.</li>
 * </ul>
 *
 * <p>Both directions of a wrong answer bite, which is why the corpus pins each:
 * an unbounded veto loses a whole real machine ({@code examples/retrystate}), and
 * a threshold with no self-composition disjunct accepts the commonest recursive
 * sealed type of all, the two-member tree whose single recursive member rebuilds
 * itself ({@code retrystate.Layer}).
 *
 * <p>Nothing here keys off the name {@code on} or the type {@code Transition};
 * discovery is purely by shape. Consistent naming across the permitted subtypes
 * is reported as a corroborating signal in the classification reason, but is
 * never required.
 *
 * <p>That is a project-wide contract and not a local one, and <b>F22</b> is where
 * it was made true. Two name lists elsewhere contradicted it: the F2 mutation
 * recognizer admitted a method whose NAME was one of six conventional setter words
 * regardless of what its body did, and the polymorphic event label was elided
 * against a list of English words held to be "neutral". A word list is a claim
 * about vocabulary rather than about the program, and both failed in the direction
 * that fabricates — an audit hook called {@code become} published its argument as a
 * resolved successor, and two corpus machines spelling their one transition
 * {@code on} and {@code wrap} labelled every edge with the transition function's
 * own name. Both lists are gone. Where a simple name still appears in the analysis
 * it is a PREFILTER over a decision taken elsewhere (the mutator lookup, keyed on
 * the callee's declaration) or a value the model itself discriminates with (a
 * per-state method name, kept only where the hierarchy spells more than one), and
 * every residual name match is reported as a diagnostic rather than passing for a
 * proof.
 */
public final class CarrierTransitionDetector {

    private CarrierTransitionDetector() {
    }

    /** How a method's returned expressions use the hierarchy values they build. */
    public enum Shape {
        /** No hierarchy value is produced by any returned expression. */
        NONE,
        /** Every produced hierarchy value is a terminal, peer-level result. */
        PEER,
        /** Some produced hierarchy value is nested inside another H node. */
        NESTED
    }

    /** One hierarchy value produced by a returned expression, with its position. */
    private record Production(CtExpression<?> value, boolean nested) {
    }

    /**
     * A nested production, with the hierarchy member it was found on — the unit
     * the bounded veto counts. {@code selfComposing} marks structural recursion:
     * a surviving nested argument derived from the current state, which is a
     * complete tree rewrite on its own and needs no corroboration.
     */
    public record NestedProduction(String member, CtExpression<?> value, boolean selfComposing) {
    }

    // ---- public predicate -----------------------------------------------------

    /**
     * Classify a method by the position of the hierarchy values its returned
     * expressions produce. This is the sibling-vs-nested predicate; it is the
     * precision guard that keeps the widened recognizer from swallowing
     * compositional sealed types.
     *
     * <p>Only <em>returned</em> (or yielded) expressions count. A hierarchy value
     * constructed and passed to a void method — {@code ctx.setState(new Locked())}
     * — is the mutation encoding, handled elsewhere, and is deliberately invisible
     * here so the two paths cannot both claim the same hierarchy.
     */
    public static Shape shapeOf(CtMethod<?> method, Set<String> hierarchy, String rootQualifiedName) {
        if (method == null || method.getBody() == null || method.isStatic()) {
            return Shape.NONE;
        }
        CtTypeReference<?> ret = method.getType();
        if (ret == null || "void".equals(ret.getSimpleName())) {
            return Shape.NONE; // a void method returns no successor
        }
        List<Production> productions = productionsOf(method, hierarchy);
        if (productions.isEmpty()) {
            return Shape.NONE;
        }
        for (Production p : productions) {
            if (p.nested()) return Shape.NESTED;
        }
        return Shape.PEER;
    }

    /**
     * Transition methods of the carrier encoding: instance methods declared on a
     * <em>permitted subtype</em> of {@code root} whose returned expressions
     * produce peer members of the hierarchy.
     *
     * <p>Methods on the sealed root itself are excluded: an abstract declaration
     * carries no body, and a {@code default} helper on the interface has no single
     * from-state to attribute an edge to.
     *
     * <p>A {@code NESTED}-shaped method is included too (F20). Excluding it made a
     * single compositional expression delete every <em>peer</em> edge the same
     * method produced — the unbounded veto one level down, and a silent drop.
     * Now that {@link #composesItself} only rejects a hierarchy on hierarchy-wide
     * evidence, a method that survives into an accepted machine must be walked;
     * its nested production is downgraded per edge by the extractor, which records
     * it as unresolved rather than losing it.
     */
    public static List<CtMethod<?>> findCarrierTransitionMethods(CtType<?> root) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();
        List<CtMethod<?>> out = new ArrayList<>();
        for (CtType<?> member : StateMachineClassifier.hierarchyTypes(root)) {
            if (member.getQualifiedName().equals(rootQn)) continue;
            for (CtMethod<?> m : member.getMethods()) {
                if (shapeOf(m, hierarchy, rootQn) != Shape.NONE) out.add(m);
            }
        }
        return out;
    }

    /**
     * The compositional veto: is this hierarchy a recursive data type — members
     * built <em>out of</em> other members — rather than an automaton? A tree's
     * "next node" is a child, not a successor state, so the whole type is rejected.
     *
     * <p>This is a gate on <em>every</em> acceptance path, not only the carrier
     * one. A recursive sealed type reached the distributed recognizer just as
     * easily — a record component of the hierarchy type gives every such type a
     * synthesised accessor "returning the hierarchy type" — so applying the guard
     * only where it was introduced would leave the same false positive standing.
     * An explicit {@code @Fsm} marker still wins: an author's opt-in outranks a
     * structural heuristic.
     *
     * <p><b>F20 — the veto is bounded.</b> It used to fire on a single nested
     * production anywhere, which is a whole-hierarchy verdict drawn from one
     * expression. {@code record Retrying(LcpState previous, int attempts)} is an
     * ordinary retry state, and any producer writing {@code new Retrying(this,
     * n + 1)} sank the entire machine. Two things changed: carrying the current
     * state stopped counting as nesting at all (see
     * {@link #nestsHierarchyValue}), and what remains must be evidence about the
     * TYPE before it may condemn the type — either
     * <ul>
     *   <li>one <b>self-composing</b> production, whose surviving nested argument
     *       is derived from the current state ({@code new Neg(operand.simplify()
     *       .result())}). That is structural recursion by definition and is
     *       sufficient alone — without this disjunct a two-member tree whose one
     *       recursive member rebuilds itself, the commonest recursive sealed type
     *       in Java, would be accepted as an automaton; or</li>
     *   <li>at least two nested productions across at least two distinct members,
     *       the threshold that catches a composition distributed over the type
     *       ({@code examples/nestedroots}' {@code Node}, whose {@code Pair} and
     *       {@code Wrap} each build the other around a foreign {@code Node}).</li>
     * </ul>
     *
     * <p>A lone, non-self-composing production is deliberately NOT a veto. It is
     * downgraded to a per-edge unresolved transition by the extractor: one
     * expression the analysis declines to read as a successor, recorded as the gap
     * it is, instead of a machine deleted on its evidence.
     */
    public static boolean composesItself(CtType<?> root) {
        List<NestedProduction> nested = nestedProductions(root);
        Set<String> members = new LinkedHashSet<>();
        for (NestedProduction n : nested) {
            if (n.selfComposing()) return true;
            members.add(n.member());
        }
        return nested.size() >= 2 && members.size() >= 2;
    }

    /**
     * Every nested production declared on the hierarchy, with the member that owns
     * it — the evidence {@link #composesItself} weighs. Exposed so a test can
     * assert WHY a hierarchy was (or was not) vetoed rather than only that it was.
     */
    public static List<NestedProduction> nestedProductions(CtType<?> root) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        List<NestedProduction> out = new ArrayList<>();
        for (CtType<?> member : StateMachineClassifier.hierarchyTypes(root)) {
            for (CtMethod<?> m : member.getMethods()) {
                if (m.getBody() == null || m.isStatic()) continue;
                CtTypeReference<?> ret = m.getType();
                if (ret == null || "void".equals(ret.getSimpleName())) continue;
                for (Production p : productionsOf(m, hierarchy)) {
                    if (!p.nested()) continue;
                    out.add(new NestedProduction(member.getQualifiedName(), p.value(),
                            composesFromOwnParts(p.value(), hierarchy)));
                }
            }
        }
        return out;
    }

    /**
     * Does {@code root} qualify as a state machine under the carrier encoding?
     *
     * <p>Three conditions, all necessary:
     * <ol>
     *   <li>{@link #composesItself} is false — the compositional veto;</li>
     *   <li>carrier transition methods are declared on at least two distinct
     *       permitted subtypes — one producing subtype is more consistent with a
     *       factory or a normaliser than with an automaton;</li>
     *   <li>at least one production names a subtype <em>other</em> than its
     *       declaring one. A hierarchy whose members only ever rebuild themselves
     *       has no edges between states and so no automaton to extract.</li>
     * </ol>
     */
    public static boolean qualifies(CtType<?> root) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();

        if (composesItself(root)) return false; // (1)

        Set<String> declaringSubtypes = new LinkedHashSet<>();
        boolean crossState = false;
        for (CtMethod<?> m : findCarrierTransitionMethods(root)) {
            CtType<?> declaring = m.getDeclaringType();
            if (declaring == null) continue;
            declaringSubtypes.add(declaring.getQualifiedName());
            for (Production p : productionsOf(m, hierarchy)) {
                String target = targetQualifiedName(p.value());
                if (target != null && !target.equals(declaring.getQualifiedName())
                        && !target.equals(rootQn)) {
                    crossState = true;
                }
            }
        }
        // (2) and (3)
        return declaringSubtypes.size() >= 2 && crossState;
    }

    /**
     * The transition-method name shared by every carrier method, when there is
     * one — a corroborating signal reported in the classification reason. Returns
     * {@code null} when the methods disagree, which is not itself disqualifying.
     */
    public static String consistentMethodName(List<CtMethod<?>> carrierMethods) {
        String name = null;
        for (CtMethod<?> m : carrierMethods) {
            if (name == null) {
                name = m.getSimpleName();
            } else if (!name.equals(m.getSimpleName())) {
                return null;
            }
        }
        return name;
    }

    /**
     * The sibling-vs-nested predicate applied to a single produced value, exposed
     * so the centralized-dispatch recognizer applies the <em>same</em> guard rather
     * than growing a second, subtly different notion of "recursive data type".
     * True when {@code value} <em>constructs</em> a hierarchy node out of other
     * hierarchy values — {@code new Add(simplify(l), simplify(r))} — and false for
     * a peer production such as {@code new SynReceived(true)}.
     *
     * <p>Only a {@link CtConstructorCall} counts as the nesting node, and that
     * restriction is load-bearing. Passing the current state to a helper that
     * computes the successor — {@code case Idle s -> fromIdle(s, event)}, the
     * ordinary way a large centralized switch is factored — puts a hierarchy value
     * in the argument list of a hierarchy-returning call, which is delegation, not
     * composition. Treating an invocation as a nesting node would veto every
     * machine written that way, including the one this predicate is meant to
     * protect. A tree builder is recognised by the node it BUILDS, not by what it
     * passes around.
     */
    public static boolean nestsHierarchyValue(CtExpression<?> value, Set<String> hierarchy) {
        if (!isHierarchyConstruction(value, hierarchy)) return false;
        return buildsFromHierarchy(value, hierarchy);
    }

    /**
     * Is this nested production <em>structural recursion</em> — a node rebuilt out
     * of its own parts? True when a surviving nested argument READS the current
     * state without being it: {@code new Neg(operand.simplify().result())} takes
     * {@code this.operand} apart and reassembles a {@code Neg} around it, and
     * {@code case Add a -> new Add(fold(a.left()), fold(a.right()))} does the same
     * through the arm's binding.
     *
     * <p>This is what lets {@link #composesItself} reject a recursive type on a
     * single production without also rejecting a machine whose one nested
     * expression happens to mention a foreign hierarchy value. A fold is defined by
     * descending into the value it matched; nothing else about a constructor
     * argument list says "tree" that loudly.
     */
    public static boolean composesFromOwnParts(CtExpression<?> value, Set<String> hierarchy) {
        if (!isHierarchyConstruction(value, hierarchy)) return false;
        for (CtExpression<?> arg : argumentsOf(value)) {
            if (isCurrentState(arg)) continue;
            if (!containsHierarchyValue(arg, hierarchy)) continue;
            if (readsCurrentState(arg, hierarchy)) return true;
        }
        return false;
    }

    /** A {@code new C(...)} whose C is in the hierarchy — the only nesting node. */
    private static boolean isHierarchyConstruction(CtExpression<?> value, Set<String> hierarchy) {
        if (!(value instanceof CtConstructorCall<?> cc)) return false;
        CtTypeReference<?> t = cc.getType();
        return t != null && hierarchy.contains(t.getQualifiedName());
    }

    // ---- F20: the current state, and its parts --------------------------------

    /**
     * Does this expression denote <em>the state currently being succeeded</em>?
     * Three spellings, and they are exactly the three ways a walk knows the
     * from-state: bare {@code this} inside a state class, the selector the
     * enclosing dispatch discriminates, and the type-pattern binding of the arm or
     * chain link that matched.
     *
     * <p>Handing that value to a constructor is a predecessor pointer, not
     * composition — {@code new Retrying(this, n + 1)} replaces the state with one
     * that remembers it. A <em>part</em> of the current state is a different thing
     * and is not covered here: {@code this.left} and {@code n.operand()} are what a
     * tree rewrite passes, and they must keep nesting.
     */
    private static boolean isCurrentState(CtExpression<?> e) {
        if (e instanceof CtThisAccess<?>) return true;
        if (!(e instanceof CtVariableRead<?> read)) return false;
        CtVariableReference<?> ref = read.getVariable();
        if (ref == null) return false;
        CtVariable<?> decl = ref.getDeclaration();
        // Walk OUT to the enclosing executable, because the arm that matched the
        // from-state need not be the innermost one: `case Waiting w -> switch (tick)
        // { case MISS -> new Waiting(w, ...) }` reads the outer arm's binding from
        // inside an inner switch over the event.
        for (CtElement p = e.getParent(); p != null; p = p.getParent()) {
            if (p instanceof CtExecutable<?>) return false;
            if (p instanceof CtCase<?> c && bindsPatternVariable(c, ref, decl)) return true;
            if (p instanceof CtAbstractSwitch<?> sw && sameVariable(sw.getSelector(), ref, decl)) {
                return true;
            }
            if (p instanceof CtIf ctIf && discriminates(ctIf.getCondition(), ref, decl)) return true;
        }
        return false;
    }

    /**
     * Is {@code ref} a pattern binding of this arm's LABELS? Matched on the
     * declaration when Spoon resolves one and on the name when it does not — a
     * record-pattern binding has no resolvable declaration in the model, so
     * identity is simply unavailable there and the name is all that is left. Only
     * the labels are scanned; a local declared in the arm's body is an ordinary
     * local and must not be mistaken for the matched state.
     */
    private static boolean bindsPatternVariable(CtCase<?> c, CtVariableReference<?> ref,
                                                CtVariable<?> decl) {
        List<CtExpression<?>> labels;
        try {
            labels = new ArrayList<>(c.getCaseExpressions());
        } catch (Throwable ignored) {
            return false;
        }
        for (CtExpression<?> label : labels) {
            for (CtVariable<?> v : label.getElements(new TypeFilter<>(CtVariable.class))) {
                if (!ref.getSimpleName().equals(v.getSimpleName())) continue;
                if (decl == null || decl == v) return true;
            }
        }
        return false;
    }

    /**
     * Does this condition discriminate the variable's dynamic type — either by
     * testing it ({@code s instanceof Retrying}) or by binding it
     * ({@code state instanceof Retrying r})? Both make the value inside the branch
     * the state that was matched.
     */
    private static boolean discriminates(CtExpression<?> cond, CtVariableReference<?> ref,
                                         CtVariable<?> decl) {
        if (cond == null) return false;
        for (CtBinaryOperator<?> bin : cond.getElements(new TypeFilter<>(CtBinaryOperator.class))) {
            if (bin.getKind() != BinaryOperatorKind.INSTANCEOF) continue;
            if (sameVariable(bin.getLeftHandOperand(), ref, decl)) return true;
            CtExpression<?> rhs = bin.getRightHandOperand();
            if (rhs == null) continue;
            for (CtVariable<?> v : rhs.getElements(new TypeFilter<>(CtVariable.class))) {
                if (!ref.getSimpleName().equals(v.getSimpleName())) continue;
                if (decl == null || decl == v) return true;
            }
        }
        return false;
    }

    /**
     * Does the expression read the current state, or a part of it, anywhere inside
     * itself? A field of a hierarchy member counts — read unqualified inside a
     * record body, {@code operand} IS {@code this.operand}, and Spoon's implicit
     * {@code this} target is not something to depend on.
     */
    private static boolean readsCurrentState(CtExpression<?> e, Set<String> hierarchy) {
        if (e == null) return false;
        if (touchesCurrentState(e, hierarchy)) return true;
        for (CtExpression<?> sub : e.getElements(new TypeFilter<>(CtExpression.class))) {
            if (sub != e && touchesCurrentState(sub, hierarchy)) return true;
        }
        return false;
    }

    private static boolean touchesCurrentState(CtExpression<?> e, Set<String> hierarchy) {
        // An EXPLICIT `this` only. Spoon gives every unqualified call to an
        // instance method an implicit `this` target, so counting those would make
        // `new Boxed(helper())` structural recursion and veto the hierarchy on a
        // receiver nobody wrote — the unbounded veto returning through a side door.
        // The shape that matters here is reading a PART, and a field read carries
        // its own evidence: the branch below fires on `this.operand` and on the
        // unqualified `operand` alike, without depending on the implicit target.
        if (e instanceof CtThisAccess<?> && !e.isImplicit()) return true;
        if (e instanceof CtFieldRead<?> fr) {
            CtFieldReference<?> ref = fr.getVariable();
            CtTypeReference<?> owner = ref == null ? null : ref.getDeclaringType();
            if (owner != null && hierarchy.contains(owner.getQualifiedName())) return true;
        }
        return isCurrentState(e);
    }

    /**
     * The same variable, decided on DECLARATION IDENTITY with the simple name as a
     * prefilter and as the fallback when Spoon cannot bind one — the rule F13
     * established, for the same reason: {@code CtElement} has deep structural
     * equality, so {@code equals} would call two disjoint arms' locals the same
     * variable.
     */
    private static boolean sameVariable(CtExpression<?> expr, CtVariableReference<?> ref,
                                        CtVariable<?> decl) {
        if (!(expr instanceof CtVariableRead<?> read) || read.getVariable() == null) return false;
        CtVariableReference<?> other = read.getVariable();
        if (!other.getSimpleName().equals(ref.getSimpleName())) return false;
        CtVariable<?> otherDecl = other.getDeclaration();
        if (decl == null || otherDecl == null) return true; // unbindable: the name stands in
        return decl == otherDecl;
    }


    // ---- production scanning --------------------------------------------------

    /** Every hierarchy value produced by the method's returned/yielded expressions. */
    private static List<Production> productionsOf(CtMethod<?> method, Set<String> hierarchy) {
        List<Production> out = new ArrayList<>();
        if (method.getBody() == null) return out;
        // A flat scan is sufficient here: detection only asks *whether* peer states
        // are produced, never under which guard. The extractor does the structured,
        // guard-carrying walk.
        for (CtReturn<?> r : method.getBody().getElements(new TypeFilter<>(CtReturn.class))) {
            scanProduced(r.getReturnedExpression(), hierarchy, out);
        }
        for (CtYieldStatement y : method.getBody().getElements(new TypeFilter<>(CtYieldStatement.class))) {
            scanProduced(y.getExpression(), hierarchy, out);
        }
        return out;
    }

    /**
     * Scan one produced expression for hierarchy values, recording each with
     * whether it sits in a nested (compositional) position.
     *
     * <p>Descent stops one level below the returned expression: into the arguments
     * of a shallow carrier call, and no further. That bound is the intra-procedural
     * scope line — a successor computed by a helper is left undiscovered here and
     * surfaces downstream as an unresolved edge rather than a guess.
     */
    private static void scanProduced(CtExpression<?> expr, Set<String> hierarchy, List<Production> out) {
        if (expr == null) return;

        // A ternary chooses between two produced values; both are at this level.
        if (expr instanceof CtConditional<?> cond) {
            scanProduced(cond.getThenExpression(), hierarchy, out);
            scanProduced(cond.getElseExpression(), hierarchy, out);
            return;
        }

        // (a) the returned expression *is* the successor: `return new Listen();`
        //     or `return this;` — a bare, terminal H value.
        if (isHierarchyValue(expr, hierarchy)) {
            out.add(new Production(expr, buildsFromHierarchy(expr, hierarchy)));
            return;
        }

        // (b) the successor is wrapped: `return Transition.to(new LastAck(), ...)`.
        //     Only a call whose own type is OUTSIDE the hierarchy is a carrier; a
        //     call producing another H node is composition, handled by (a).
        for (CtExpression<?> arg : argumentsOf(expr)) {
            if (arg instanceof CtConditional<?> c) {
                scanProduced(c.getThenExpression(), hierarchy, out);
                scanProduced(c.getElseExpression(), hierarchy, out);
            } else if (isHierarchyValue(arg, hierarchy)) {
                out.add(new Production(arg, buildsFromHierarchy(arg, hierarchy)));
            }
        }
    }

    /**
     * The nesting test, applied to a produced H value: does it take another
     * hierarchy value as a construction argument? {@code new Add(l.simplify(),
     * r.simplify())} does — it makes a bigger H out of smaller ones, which is
     * composition, not succession. {@code new SynReceived(true)} does not.
     *
     * <p>F20: an argument that IS the current state is skipped. {@code new
     * Retrying(this, n + 1)} does not make a bigger H out of smaller ones — it
     * makes a successor that remembers the state it replaced, which is what a
     * retry or backoff state is for. Structural recursion descends into an
     * argument's parts and so never passes the argument whole; only that
     * whole-value case is skipped, and {@code new Wrap(this.left)} still nests.
     */
    private static boolean buildsFromHierarchy(CtExpression<?> value, Set<String> hierarchy) {
        for (CtExpression<?> arg : argumentsOf(value)) {
            if (isCurrentState(arg)) continue;
            if (containsHierarchyValue(arg, hierarchy)) return true;
        }
        return false;
    }

    /**
     * Does the expression subtree yield a hierarchy value anywhere inside it?
     *
     * <p>A RECEIVER does not count (F20). {@code r.attempts()} reads an {@code int}
     * off a hierarchy value; the value is consumed by the read, not nested in
     * anything, yet a flat subtree scan saw the receiver and reported the whole
     * argument as a hierarchy value. Every payload computed from the current state
     * — the commonest thing a carried counter is — read as composition. A receiver
     * whose own result is a hierarchy value ({@code n.operand()}) still nests: it
     * is caught as the argument it is, one level up.
     */
    private static boolean containsHierarchyValue(CtExpression<?> arg, Set<String> hierarchy) {
        if (arg == null) return false;
        if (isHierarchyValue(arg, hierarchy)) return true;
        for (CtExpression<?> sub : arg.getElements(new TypeFilter<>(CtExpression.class))) {
            if (sub == arg || isReceiver(sub)) continue;
            if (isHierarchyValue(sub, hierarchy)) return true;
        }
        return false;
    }

    /** Is this expression only the target of a field read or an invocation? */
    private static boolean isReceiver(CtExpression<?> sub) {
        return sub.getParent() instanceof CtTargetedExpression<?, ?> te && te.getTarget() == sub;
    }

    /**
     * Is {@code e} a value belonging to the hierarchy? {@code this} inside a state
     * class always is; otherwise the static type decides. Under {@code noClasspath}
     * an unresolvable type simply reads as "not a hierarchy value", which loses a
     * production rather than inventing one.
     */
    private static boolean isHierarchyValue(CtExpression<?> e, Set<String> hierarchy) {
        if (e == null) return false;
        if (e instanceof CtThisAccess<?>) return true;
        if (e instanceof CtConstructorCall<?> cc) {
            CtTypeReference<?> t = cc.getType();
            return t != null && hierarchy.contains(t.getQualifiedName());
        }
        CtTypeReference<?> t = e.getType();
        return t != null && hierarchy.contains(t.getQualifiedName());
    }

    /** The concrete state a production names, or {@code null} when it is {@code this}/opaque. */
    private static String targetQualifiedName(CtExpression<?> value) {
        if (value instanceof CtConstructorCall<?> cc && cc.getType() != null) {
            return cc.getType().getQualifiedName();
        }
        if (value instanceof CtThisAccess<?>) {
            return null; // a self-loop names no sibling
        }
        CtTypeReference<?> t = value.getType();
        return t == null ? null : t.getQualifiedName();
    }

    /** Argument list of an invocation or constructor call; empty for anything else. */
    private static List<CtExpression<?>> argumentsOf(CtExpression<?> e) {
        if (e instanceof CtInvocation<?> inv) return new ArrayList<>(inv.getArguments());
        if (e instanceof CtConstructorCall<?> cc) return new ArrayList<>(cc.getArguments());
        return List.of();
    }
}
