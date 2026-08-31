package io.sealfsm.detect.dispatch;

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
import spoon.reflect.code.CtTargetedExpression;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The sibling-vs-nested predicate: is a produced hierarchy value a
 * <em>successor</em> of the current state, or a <em>node built around</em> other
 * hierarchy values?
 *
 * <p>{@code new Add(simplify(l), simplify(r))} makes a bigger H out of smaller
 * ones — a tree rewrite whose "next" is a child, not a successor. Recognising
 * that is the precision guard that keeps the widened recognizers from swallowing
 * recursive data types: tree builders, exhaustive folds, tag-dispatched
 * deserializers. Without it a record component of the hierarchy type gives such
 * a type an accessor that reads exactly like a per-state transition method.
 *
 * <p>The predicate lived in {@code CarrierTransitionDetector} and was reached
 * from the switch and chain recognizers by name. It is here now because it
 * belongs to no single locus: every acceptance path must apply the SAME guard,
 * or a rewrite written with {@code switch} and the same rewrite written with
 * {@code instanceof} get different verdicts, and two recognizers with two notions
 * of "recursive data type" eventually disagree into a false positive.
 *
 * <p><b>The veto is bounded (F20).</b> A state may legitimately REMEMBER its
 * predecessor — {@code record Retrying(Attempt previous, int n)} is an ordinary
 * retry state, and any producer writing {@code new Retrying(this, n + 1)} used to
 * sink the entire machine. Three narrowings, each closing a different half, all
 * of them below:
 *
 * <ul>
 *   <li>{@link #isCurrentState} — carrying the current state WHOLE is not
 *       nesting. Structural recursion descends into an argument's parts and so
 *       never passes the argument whole. A <em>part</em> ({@code this.left},
 *       {@code n.operand()}) still nests.</li>
 *   <li>{@link #containsHierarchyValue} — a RECEIVER is not an argument.
 *       {@code r.attempts() + 1} is an {@code int} whose only hierarchy-typed
 *       node is the receiver; counting it read every payload computed from the
 *       current state as composition.</li>
 *   <li>{@link #composesFromOwnParts} — one self-composing production is a fold
 *       on its own; anything else needs ≥2 nested productions across ≥2 distinct
 *       members before a whole hierarchy is vetoed. The callers apply that
 *       threshold; this class supplies the two predicates it is written in terms
 *       of, so switch, chain and carrier all weigh identical evidence.</li>
 * </ul>
 */
public final class CompositionVeto {

    private CompositionVeto() {
    }

    /**
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
     * machine written that way, including the ones this predicate protects. A tree
     * builder is recognised by the node it BUILDS, not by what it passes around.
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
     * <p>This is what lets a recursive type be rejected on a single production
     * without also rejecting a machine whose one nested expression happens to
     * mention a foreign hierarchy value. A fold is defined by descending into the
     * value it matched; nothing else about a constructor argument list says "tree"
     * that loudly.
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

    /** {@link #nestsHierarchyValue}, seeing through a ternary's two branches. */
    public static boolean nestsThroughTernary(CtExpression<?> value, Set<String> hierarchy) {
        if (value == null) return false;
        if (value instanceof CtConditional<?> cond) {
            return nestsThroughTernary(cond.getThenExpression(), hierarchy)
                    || nestsThroughTernary(cond.getElseExpression(), hierarchy);
        }
        return nestsHierarchyValue(value, hierarchy);
    }

    /** {@link #composesFromOwnParts}, seeing through a ternary's two branches. */
    public static boolean composesThroughTernary(CtExpression<?> value, Set<String> hierarchy) {
        if (value == null) return false;
        if (value instanceof CtConditional<?> cond) {
            return composesThroughTernary(cond.getThenExpression(), hierarchy)
                    || composesThroughTernary(cond.getElseExpression(), hierarchy);
        }
        return composesFromOwnParts(value, hierarchy);
    }

    /** A {@code new C(...)} whose C is in the hierarchy — the only nesting node. */
    public static boolean isHierarchyConstruction(CtExpression<?> value, Set<String> hierarchy) {
        if (!(value instanceof CtConstructorCall<?> cc)) return false;
        CtTypeReference<?> t = cc.getType();
        return t != null && hierarchy.contains(t.getQualifiedName());
    }

    /**
     * The nesting test applied to a produced H value: does it take another
     * hierarchy value as a construction argument?
     *
     * <p>F20: an argument that IS the current state is skipped. {@code new
     * Retrying(this, n + 1)} does not make a bigger H out of smaller ones — it
     * makes a successor that remembers the state it replaced, which is what a
     * retry or backoff state is for.
     */
    public static boolean buildsFromHierarchy(CtExpression<?> value, Set<String> hierarchy) {
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
     * argument as a hierarchy value. A receiver whose own result is a hierarchy
     * value ({@code n.operand()}) still nests: it is caught as the argument it is,
     * one level up.
     */
    public static boolean containsHierarchyValue(CtExpression<?> arg, Set<String> hierarchy) {
        if (arg == null) return false;
        if (isHierarchyValue(arg, hierarchy)) return true;
        for (CtExpression<?> sub : arg.getElements(new TypeFilter<>(CtExpression.class))) {
            if (sub == arg || isReceiver(sub)) continue;
            if (isHierarchyValue(sub, hierarchy)) return true;
        }
        return false;
    }

    /**
     * Is {@code e} a value belonging to the hierarchy? {@code this} inside a state
     * class always is; otherwise the static type decides. Under {@code noClasspath}
     * an unresolvable type simply reads as "not a hierarchy value", which loses a
     * production rather than inventing one.
     */
    public static boolean isHierarchyValue(CtExpression<?> e, Set<String> hierarchy) {
        if (e == null) return false;
        if (e instanceof CtThisAccess<?>) return true;
        if (e instanceof CtConstructorCall<?> cc) {
            CtTypeReference<?> t = cc.getType();
            return t != null && hierarchy.contains(t.getQualifiedName());
        }
        CtTypeReference<?> t = e.getType();
        return t != null && hierarchy.contains(t.getQualifiedName());
    }

    /** Argument list of an invocation or constructor call; empty for anything else. */
    public static List<CtExpression<?>> argumentsOf(CtExpression<?> e) {
        if (e instanceof CtInvocation<?> inv) return new ArrayList<>(inv.getArguments());
        if (e instanceof CtConstructorCall<?> cc) return new ArrayList<>(cc.getArguments());
        return List.of();
    }

    // ---- the current state, and its parts -------------------------------------

    /**
     * Does this expression denote <em>the state currently being succeeded</em>?
     * Three spellings, and they are exactly the three ways a walk knows the
     * from-state: bare {@code this} inside a state class, the selector the
     * enclosing dispatch discriminates, and the type-pattern binding of the arm or
     * chain link that matched.
     *
     * <p>Handing that value to a constructor is a predecessor pointer, not
     * composition. A <em>part</em> of the current state is a different thing and is
     * not covered here: {@code this.left} and {@code n.operand()} are what a tree
     * rewrite passes, and they must keep nesting.
     */
    public static boolean isCurrentState(CtExpression<?> e) {
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
    public static boolean readsCurrentState(CtExpression<?> e, Set<String> hierarchy) {
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

    /** Is this expression only the target of a field read or an invocation? */
    private static boolean isReceiver(CtExpression<?> sub) {
        return sub.getParent() instanceof CtTargetedExpression<?, ?> te && te.getTarget() == sub;
    }
}
