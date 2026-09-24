package io.sealfsm.detect;

import io.sealfsm.detect.dispatch.Commit;
import io.sealfsm.detect.dispatch.CommitProbe;
import io.sealfsm.detect.dispatch.MutatorRecognizer;
import io.sealfsm.model.CommitForm;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * F33 — the GoF State pattern: a per-state method that installs its successor
 * into a <em>context</em> rather than returning it.
 *
 * <pre>{@code
 *   public final class CreatedState implements OrderState {
 *       public void pay(Order order)    { order.changeState(new PaidState()); }
 *       public void cancel(Order order) { order.changeState(new CancelledState()); }
 *   }
 * }</pre>
 *
 * <p>This is {@code POLYMORPHIC_OVERRIDE} dispatch — the receiver's dynamic type
 * selects the body, so the source state is the declaring class, exactly as for
 * {@code examples/traffic} — with a {@code MUTATOR_ARGUMENT} or
 * {@code FIELD_MUTATION} commit. Every recognizer keyed the override locus on the
 * CODOMAIN ("returns H", or "returns a carrier of H"), so a {@code void} per-state
 * method matched nothing, and the textbook spelling of the State pattern was
 * recovered only behind an explicit {@code @Fsm} marker, through the F2 fallback,
 * which has no locus. FIXLOG's reachability table marked those two cells
 * {@code R*} for that reason.
 *
 * <p><b>What a commit is here.</b> A statement in the method's OWN body (not in a
 * lambda or local class it declares) that installs a hierarchy value into a
 * holder <em>outside</em> the hierarchy:
 * <ul>
 *   <li>a call to a structural mutator ({@link MutatorRecognizer}: one parameter
 *       typed with the root, committed from that parameter into an H-typed
 *       field) whose declaring type is not a member of H; or</li>
 *   <li>a write to a field declared with the ROOT type ({@link CommitProbe}'s
 *       clause) whose declaring type is not a member of H.</li>
 * </ul>
 * Both halves reuse the existing rules. The one new condition is "outside H",
 * and it is load-bearing: a member writing an H-typed slot of a member
 * ({@code this.left = new Leaf()}) is a tree mutating itself, which is
 * composition, while the State pattern's slot lives in the context by
 * definition. Nothing keys on a name (F22).
 *
 * <p><b>Hierarchy-wide threshold.</b> This is the carrier path's
 * {@link CarrierTransitionDetector#qualifies}, for the same reason. A
 * side-effecting method is weaker evidence than a codomain, so a single member
 * doing it is a check rather than a dispatch. The hierarchy qualifies when
 * committing methods sit on ≥2 distinct members and ≥1 production names a state
 * other than its own. The second clause is what rejects an echo:
 * {@code bus.publish(this)} on every member of a message hierarchy installs a
 * hierarchy value into a holder and moves nothing.
 */
public final class ContextCommitDetector {

    private ContextCommitDetector() {
    }

    /** One context commit inside a per-state method: its form and the installed value. */
    public record ContextCommit(CommitForm form, spoon.reflect.code.CtExpression<?> value,
                                CtElement at) { }

    /**
     * Instance methods with a body, declared on the root or any member, whose own
     * body performs at least one context commit. A method returning H belongs to
     * the distributed recognizer and one returning a carrier to the carrier path,
     * so both are excluded here. Two walkers would claim one body.
     */
    public static List<CtMethod<?>> findContextCommitMethods(CtType<?> root) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();
        Set<CtMethod<?>> carriers = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        carriers.addAll(CarrierTransitionDetector.findCarrierTransitionMethods(root));
        List<CtMethod<?>> out = new ArrayList<>();
        for (CtType<?> member : StateMachineClassifier.hierarchyTypes(root)) {
            for (CtMethod<?> m : member.getMethods()) {
                if (m.isStatic() || m.getBody() == null || carriers.contains(m)) continue;
                CtTypeReference<?> ret = m.getType();
                if (ret != null && hierarchy.contains(ret.getQualifiedName())) continue;
                if (!commitsOf(m, hierarchy, rootQn).isEmpty()) out.add(m);
            }
        }
        return out;
    }

    /** The context commits in {@code m}'s own body, in source order. */
    public static List<ContextCommit> commitsOf(CtMethod<?> m, Set<String> hierarchy, String rootQn) {
        List<ContextCommit> out = new ArrayList<>();
        try {
            for (CtElement e : m.getBody().getElements(new TypeFilter<>(CtElement.class))) {
                if (e.getParent(CtExecutable.class) != m) continue; // a lambda's / local class's own
                ContextCommit c = null;
                if (e instanceof CtInvocation<?> inv) c = commitOfCall(inv, hierarchy, rootQn);
                else if (e instanceof CtAssignment<?, ?> a) c = commitOfWrite(a, hierarchy, rootQn);
                if (c != null) out.add(c);
            }
        } catch (Throwable ignored) {
            // an unreadable body commits nothing that can be shown
        }
        return out;
    }

    /** A mutator call whose mutator lives outside H, or {@code null}. */
    public static ContextCommit commitOfCall(CtInvocation<?> inv, Set<String> hierarchy, String rootQn) {
        Commit commit = MutatorRecognizer.commitOfCall(inv, hierarchy, rootQn);
        if (commit == null) return null;
        try {
            var decl = inv.getExecutable().getExecutableDeclaration();
            CtType<?> owner = decl instanceof CtMethod<?> cm ? cm.getDeclaringType() : null;
            if (owner == null || hierarchy.contains(owner.getQualifiedName())) return null;
        } catch (Throwable t) {
            return null;
        }
        return new ContextCommit(CommitForm.MUTATOR_ARGUMENT, commit.value(), inv);
    }

    /** A root-typed field write whose field is declared outside H, or {@code null}. */
    public static ContextCommit commitOfWrite(CtAssignment<?, ?> a, Set<String> hierarchy, String rootQn) {
        if (!CommitProbe.isRootFieldWrite(a, hierarchy, rootQn)) return null;
        try {
            if (!(a.getAssigned() instanceof CtVariableAccess<?> va)
                    || !(va.getVariable() instanceof CtFieldReference<?> f)) {
                return null;
            }
            CtTypeReference<?> owner = f.getDeclaringType();
            // An owner that did not resolve cannot be shown to be outside H: declining
            // costs an acceptance, guessing could admit a tree writing its own slot.
            if (owner == null || hierarchy.contains(owner.getQualifiedName())) return null;
        } catch (Throwable t) {
            return null;
        }
        return new ContextCommit(CommitForm.FIELD_MUTATION, a.getAssignment(), a);
    }

    /** The carrier threshold, asked of context-committing methods. */
    public static boolean qualifies(CtType<?> root) {
        if (CarrierTransitionDetector.composesItself(root)) return false;
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();
        Set<String> declaring = new LinkedHashSet<>();
        boolean crossState = false;
        for (CtMethod<?> m : findContextCommitMethods(root)) {
            CtType<?> d = m.getDeclaringType();
            if (d == null) continue;
            declaring.add(d.getQualifiedName());
            for (ContextCommit c : commitsOf(m, hierarchy, rootQn)) {
                String target = CarrierTransitionDetector.targetQualifiedName(c.value());
                if (target != null && !target.equals(d.getQualifiedName()) && !target.equals(rootQn)) {
                    crossState = true;
                }
            }
        }
        return declaring.size() >= 2 && crossState;
    }

    /**
     * Does {@code m}'s body <em>reject</em> its input, i.e. is its last top-level
     * statement a {@code throw}? A rejecting cell is an undefined input, not an
     * unresolved successor. The extractor counts rejecting cells and reports them
     * rather than emitting edges for them (the D3/F9 rule, at this locus).
     */
    public static boolean rejects(CtMethod<?> m) {
        CtBlock<?> body = m.getBody();
        if (body == null) return false;
        List<CtStatement> sts = body.getStatements();
        return !sts.isEmpty() && sts.get(sts.size() - 1) instanceof CtThrow;
    }
}
