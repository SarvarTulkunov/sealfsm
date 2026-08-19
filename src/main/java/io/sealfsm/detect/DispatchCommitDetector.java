package io.sealfsm.detect;

import io.sealfsm.model.CommitForm;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recognises <em>centralized dispatch</em> by the switch itself rather than by
 * the signature of the method hosting it.
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
     * @param dispatch the switch over H itself — the extractor walks exactly this
     *                 node, never the whole host body, so unrelated statements
     *                 around it (a trailing {@code return this.state;}) cannot
     *                 turn into spurious edges
     * @param commit   how the switch's result becomes the machine's state
     */
    public record Producer(CtMethod<?> host, CtAbstractSwitch<?> dispatch, CommitForm commit) {
    }

    /**
     * Every transition producer for {@code root} in the model, in source order.
     * Both {@code switch} statements and {@code switch} expressions are scanned:
     * the expression form covers all four idioms above, and the statement form is
     * reached when its arms individually commit (handled downstream by the walker).
     */
    public static List<Producer> find(CtType<?> root, CtModel model) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        List<Producer> out = new ArrayList<>();
        for (CtSwitchExpression<?, ?> sw : model.getElements(new TypeFilter<>(CtSwitchExpression.class))) {
            addIfProducer(sw, hierarchy, out);
        }
        for (CtSwitch<?> sw : model.getElements(new TypeFilter<>(CtSwitch.class))) {
            addIfProducer(sw, hierarchy, out);
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
        out.add(new Producer(host, sw, commit));
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
