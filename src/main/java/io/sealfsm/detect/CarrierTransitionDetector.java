package io.sealfsm.detect;

import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
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
 * ⇒ not FSM", and {@link #shapeOf} is its executable form. A single nested
 * production anywhere in the hierarchy vetoes the whole hierarchy: composition
 * is a property of the data type, not of one method.
 *
 * <p>Nothing here keys off the name {@code on} or the type {@code Transition};
 * discovery is purely by shape. Consistent naming across the permitted subtypes
 * is reported as a corroborating signal in the classification reason, but is
 * never required.
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
     */
    public static List<CtMethod<?>> findCarrierTransitionMethods(CtType<?> root) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();
        List<CtMethod<?>> out = new ArrayList<>();
        for (CtType<?> member : StateMachineClassifier.hierarchyTypes(root)) {
            if (member.getQualifiedName().equals(rootQn)) continue;
            for (CtMethod<?> m : member.getMethods()) {
                if (shapeOf(m, hierarchy, rootQn) == Shape.PEER) out.add(m);
            }
        }
        return out;
    }

    /**
     * The compositional veto: does any member of the hierarchy build a hierarchy
     * value <em>out of</em> other hierarchy values? One nested production anywhere
     * sinks the whole type, because composition is a property of the data type
     * rather than of a single method — a hierarchy with a recursive constructor is
     * a tree, and a tree's "next node" is a child, not a successor state.
     *
     * <p>This is a gate on <em>every</em> acceptance path, not only the carrier
     * one. A recursive sealed type reached the distributed recognizer just as
     * easily — a record component of the hierarchy type gives every such type a
     * synthesised accessor "returning the hierarchy type" — so applying the guard
     * only where it was introduced would leave the same false positive standing.
     * An explicit {@code @Fsm} marker still wins: an author's opt-in outranks a
     * structural heuristic.
     */
    public static boolean composesItself(CtType<?> root) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();
        for (CtType<?> member : StateMachineClassifier.hierarchyTypes(root)) {
            for (CtMethod<?> m : member.getMethods()) {
                if (shapeOf(m, hierarchy, rootQn) == Shape.NESTED) return true;
            }
        }
        return false;
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
        if (!(value instanceof CtConstructorCall<?> cc)) return false;
        CtTypeReference<?> t = cc.getType();
        if (t == null || !hierarchy.contains(t.getQualifiedName())) return false;
        return buildsFromHierarchy(cc, hierarchy);
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
     */
    private static boolean buildsFromHierarchy(CtExpression<?> value, Set<String> hierarchy) {
        for (CtExpression<?> arg : argumentsOf(value)) {
            if (containsHierarchyValue(arg, hierarchy)) return true;
        }
        return false;
    }

    /** Does the expression subtree yield a hierarchy value anywhere inside it? */
    private static boolean containsHierarchyValue(CtExpression<?> arg, Set<String> hierarchy) {
        if (arg == null) return false;
        if (isHierarchyValue(arg, hierarchy)) return true;
        for (CtExpression<?> sub : arg.getElements(new TypeFilter<>(CtExpression.class))) {
            if (sub != arg && isHierarchyValue(sub, hierarchy)) return true;
        }
        return false;
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
