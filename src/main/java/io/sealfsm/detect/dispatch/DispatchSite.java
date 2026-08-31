package io.sealfsm.detect.dispatch;

import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.util.List;

/**
 * One discrimination of the current state, wherever it lives and however it is
 * spelled — the first of the two axes this package separates.
 *
 * <p>A site says only <em>that</em> the state is discriminated and into which
 * branches. What each branch does with the successor it chooses is the other
 * axis, {@link Commit}, asked per arm. Keeping them apart is what makes a
 * combination like "centralized switch whose arms return a carrier" reachable
 * by composition rather than by a fifth recognizer.
 *
 * @param locus how the discrimination is written
 * @param host the executable the discrimination sits in — a {@code CtMethod} for
 *        every named locus, a {@code CtLambda} or an anonymous-class method for
 *        {@link DispatchLocus#FUNCTIONAL_CALLABLE}. Used for ownership (one body,
 *        one walker) and to answer codomain questions about the enclosing method.
 * @param node the discrimination node itself — the {@code CtSwitch} /
 *        {@code CtSwitchExpression}, the head {@code CtIf} of a chain, or the
 *        callable for a locus that has no single node. <b>Not</b> the host body:
 *        walking a field-mutation host whole reads its trailing
 *        {@code return this.state;} as a producer with no attributable source,
 *        a fictitious edge manufactured out of plumbing.
 * @param arms the branches, in source order
 */
public record DispatchSite(DispatchLocus locus, CtElement host, CtElement node,
                           List<DispatchArm> arms) {

    public DispatchSite {
        arms = List.copyOf(arms);
    }

    /**
     * {@code declaringType#signature} — the key hosts are deduped on everywhere
     * in this codebase, and deliberately not a bare signature: two classes in one
     * model routinely declare {@code next(State, Event)}, and a bare signature
     * would let one host's walk stand in for the other's.
     *
     * <p>{@code null} for a locus whose host is not a named method; such a host is
     * identified by object identity instead, which is exactly right for a lambda —
     * it has no name to collide on.
     */
    public String hostKey() {
        if (!(host instanceof CtMethod<?> m)) return null;
        CtType<?> declaring = m.getDeclaringType();
        return (declaring == null ? "?" : declaring.getQualifiedName()) + "#" + m.getSignature();
    }

    /** The two-position encoding this site reports as. */
    public io.sealfsm.model.StateMachine.Encoding encoding() {
        return locus.encoding();
    }
}
