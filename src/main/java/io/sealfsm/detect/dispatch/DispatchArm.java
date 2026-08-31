package io.sealfsm.detect.dispatch;

import spoon.reflect.declaration.CtElement;

/**
 * One branch of a dispatch: the state it is selected for, the code it runs, and
 * whatever the selection carried besides the state.
 *
 * <p>The four loci spell an arm differently — a {@code case}, an
 * {@code else if} link, an overriding method, a lambda body — and the point of
 * this record is that nothing downstream needs to know which. An arm is
 * "in state F, on input E, subject to G, run this".
 *
 * @param fromSimpleName the state this arm is selected for, as the id the rest of
 *        the pipeline names states by (see {@link io.sealfsm.model.StateNaming} —
 *        it is not always a bare simple name, because two permitted types may
 *        legally share one). {@code null} means the arm is reached in more than
 *        one state, or in a state the analysis could not determine; it is never
 *        a stand-in for "the first state we thought of".
 * @param body the code the arm runs. Not the produced value: an arm may commit
 *        its successor several statements in, under further conditions, or not
 *        at all.
 * @param guard the data condition under which the arm is taken, as source text,
 *        or {@code null} for none. A type test that SELECTS the state belongs in
 *        {@code fromSimpleName} and must never appear here — recording it as a
 *        guard both loses the source state and decorates the edge with what is
 *        really its own label (F17).
 * @param eventLabel the input symbol the arm is selected on, or {@code null}
 *        when the dispatch does not discriminate on one.
 */
public record DispatchArm(String fromSimpleName, CtElement body, String guard, String eventLabel) {

    /** An arm whose from-state is known and which carries neither guard nor event. */
    public static DispatchArm of(String fromSimpleName, CtElement body) {
        return new DispatchArm(fromSimpleName, body, null, null);
    }

    /** True when this arm is attributed to a determined source state. */
    public boolean hasFromState() {
        return fromSimpleName != null;
    }
}
