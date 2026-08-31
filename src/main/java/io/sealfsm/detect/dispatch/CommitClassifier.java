package io.sealfsm.detect.dispatch;

import io.sealfsm.model.CommitForm;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;

import java.util.Set;

/**
 * The single definition of <em>what counts as installing a successor</em>,
 * asked at any dispatch locus.
 *
 * <p>It was previously {@code DispatchCommitDetector.commitFormOf}, reachable
 * only from the switch recognizer, which is why the commit question could not be
 * asked of an {@code instanceof} chain, an override or a lambda without each
 * growing its own version of it. This class is the same rule with the caller
 * removed from it.
 *
 * <p><b>The commit requirement IS the precision guard.</b> A transition switch
 * and an exhaustive fold are structurally identical at the discrimination —
 * {@code switch (state)} says nothing about whether the arms produce states — so
 * the codomain is the only thing separating {@code state = switch (state)} from
 * {@code label = switch (state)} yielding a {@code String}. Relaxing anything
 * here reports an automaton whose every state has zero transitions.
 *
 * <p><b>Only the immediate syntactic context is consulted.</b> Following the
 * value further — through a helper, into another object's field — crosses the
 * intra-procedural boundary, and a commit the analysis cannot see in this method
 * is a commit it must not claim.
 *
 * <p><b>The declared TYPE of the target decides, never its name.</b> Two machines
 * in one model routinely both call their field {@code state}; keying on the name
 * lets each absorb the other's assignments.
 */
public final class CommitClassifier {

    private CommitClassifier() {
    }

    /**
     * What happens to a switch's result, or {@code null} when it is not committed
     * as a hierarchy value at all.
     *
     * <p>Moved verbatim from {@code DispatchCommitDetector.commitFormOf}; the four
     * lettered cases below are its four cases, unchanged.
     */
    public static Commit classify(CtAbstractSwitch<?> sw, Set<String> hierarchy) {
        CtElement parent = parentOf(sw);
        if (parent == null) return null;

        // (a) return switch (sel) { ... };  — H only if the METHOD returns H, which
        // is what rejects `String describe() { return switch (state) {...}; }`.
        if (parent instanceof CtReturn<?> || parent instanceof CtYieldStatement) {
            CtMethod<?> host = enclosingMethod(sw);
            CtTypeReference<?> ret = host == null ? null : host.getType();
            if (ret == null) return null;
            if (hierarchy.contains(ret.getQualifiedName())) {
                return new Commit(CommitForm.VALUE_RETURN, valueOf(sw), parent);
            }
            // The codomain is outside H, but it may still CARRY a state. A wrapper
            // with exactly one hierarchy-typed component is transparent: the
            // successor is in there and the analysis can name which slot. This is
            // the combination no recognizer could reach while the axes were fused —
            // the carrier detector demanded a per-subtype override and the switch
            // detector demanded an H-typed commit, so a centralized transition
            // table returning a carrier matched neither.
            return carrierComponentOf(ret, hierarchy) != null
                    ? new Commit(CommitForm.CARRIER_RETURN, valueOf(sw), parent) : null;
        }

        // (b)/(c) this.field = switch (sel) { ... };  /  field = switch (sel) { ... };
        // (d) local = switch (sel) { ... };
        if (parent instanceof CtAssignment<?, ?> asg) {
            CommitForm form = ofTarget(asg.getAssigned(), hierarchy);
            return form == null ? null : new Commit(form, valueOf(sw), asg);
        }

        // (d) H next = switch (sel) { ... };  — declaration-site accumulator.
        if (parent instanceof CtLocalVariable<?> lv) {
            CtTypeReference<?> t = lv.getType();
            return t != null && hierarchy.contains(t.getQualifiedName())
                    ? new Commit(CommitForm.LOCAL_ACCUMULATOR, valueOf(sw), lv) : null;
        }
        return null;
    }

    /**
     * The commit form implied by an assignment target: an H-typed field is a field
     * mutation, an H-typed local is an accumulator, anything else is not a commit.
     *
     * <p>Public because a chain has no single result expression — its commit is an
     * assignment inside a branch — so the extractor asks this directly while
     * walking one. Asking rather than re-deriving is what keeps "what counts as a
     * commit" one definition.
     */
    public static CommitForm ofTarget(CtExpression<?> target, Set<String> hierarchy) {
        if (!(target instanceof CtVariableAccess<?> va) || va.getVariable() == null) return null;
        CtVariableReference<?> vref = va.getVariable();
        CtTypeReference<?> declared = vref.getType();
        if (declared == null || !hierarchy.contains(declared.getQualifiedName())) return null;
        return vref.getDeclaration() instanceof CtLocalVariable<?>
                ? CommitForm.LOCAL_ACCUMULATOR : CommitForm.FIELD_MUTATION;
    }

    /** Is writing to {@code target} how a successor gets installed? */
    public static boolean isCommitTarget(CtExpression<?> target, Set<String> hierarchy) {
        return ofTarget(target, hierarchy) != null;
    }

    /**
     * The single hierarchy-typed component of a carrier type, or {@code null}.
     *
     * <p>A "component" is a non-static field: for a {@code record} Spoon models the
     * components as fields, so this covers both spellings without depending on the
     * record API, which has moved across Spoon versions.
     *
     * <p>Three answers, and each {@code null} is a deliberate refusal:
     * <ul>
     *   <li>R is itself in H — then it is not a carrier, it is the state, and the
     *       caller has already handled that as {@code VALUE_RETURN};</li>
     *   <li>R's declaration was never read — under {@code noClasspath} an
     *       unresolvable type has no fields to inspect, and an empty field list is
     *       then evidence of nothing (the F11 rule: a body the analysis did not
     *       read may not be reasoned from);</li>
     *   <li>R has several hierarchy-typed components — which one is the successor
     *       is genuinely ambiguous, so the commit is declined rather than guessed.
     *       Same rule {@code soleEnumComponent} applies to &Sigma;.</li>
     * </ul>
     *
     * <p><b>Nothing here keys on a name.</b> Not the type's, not the field's. A
     * carrier is recognised by having a slot of the right type, which is what
     * makes {@code record Step(TcpState next, Action a)} and
     * {@code record Outcome(Action a, TcpState s)} the same shape.
     */
    public static CtTypeReference<?> carrierComponentOf(CtTypeReference<?> r,
                                                        Set<String> hierarchy) {
        if (r == null) return null;
        if (hierarchy.contains(r.getQualifiedName())) return null;
        CtType<?> decl;
        try {
            decl = r.getTypeDeclaration();
        } catch (Throwable t) {
            return null;
        }
        if (decl == null) return null;
        CtTypeReference<?> only = null;
        try {
            for (CtField<?> f : decl.getFields()) {
                if (f.hasModifier(ModifierKind.STATIC)) continue;
                CtTypeReference<?> ft = f.getType();
                if (ft == null || !hierarchy.contains(ft.getQualifiedName())) continue;
                if (only != null) return null;   // ambiguous: decline, never guess
                only = ft;
            }
        } catch (Throwable t) {
            return null;
        }
        return only;
    }

    private static CtExpression<?> valueOf(CtAbstractSwitch<?> sw) {
        return sw instanceof CtExpression<?> e ? e : null;
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
