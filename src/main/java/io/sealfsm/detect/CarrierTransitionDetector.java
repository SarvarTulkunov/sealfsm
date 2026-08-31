package io.sealfsm.detect;

import io.sealfsm.detect.dispatch.CompositionVeto;

import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
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
                            CompositionVeto.composesFromOwnParts(p.value(), hierarchy)));
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
     * The sibling-vs-nested predicate applied to a single produced value.
     *
     * <p>The rule itself now lives in {@link CompositionVeto}, which belongs to no
     * locus: every acceptance path must apply the SAME guard, or a tree rewrite
     * written with {@code switch} and the same rewrite written with
     * {@code instanceof} get different verdicts. Kept here as the name the carrier
     * path and the extractor already call it by.
     */
    public static boolean nestsHierarchyValue(CtExpression<?> value, Set<String> hierarchy) {
        return CompositionVeto.nestsHierarchyValue(value, hierarchy);
    }

    /**
     * Is this nested production <em>structural recursion</em> — a node rebuilt out
     * of its own parts? See {@link CompositionVeto#composesFromOwnParts}.
     */
    public static boolean composesFromOwnParts(CtExpression<?> value, Set<String> hierarchy) {
        return CompositionVeto.composesFromOwnParts(value, hierarchy);
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
        if (CompositionVeto.isHierarchyValue(expr, hierarchy)) {
            out.add(new Production(expr, CompositionVeto.buildsFromHierarchy(expr, hierarchy)));
            return;
        }

        // (b) the successor is wrapped: `return Transition.to(new LastAck(), ...)`.
        //     Only a call whose own type is OUTSIDE the hierarchy is a carrier; a
        //     call producing another H node is composition, handled by (a).
        for (CtExpression<?> arg : CompositionVeto.argumentsOf(expr)) {
            if (arg instanceof CtConditional<?> c) {
                scanProduced(c.getThenExpression(), hierarchy, out);
                scanProduced(c.getElseExpression(), hierarchy, out);
            } else if (CompositionVeto.isHierarchyValue(arg, hierarchy)) {
                out.add(new Production(arg, CompositionVeto.buildsFromHierarchy(arg, hierarchy)));
            }
        }
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

}
