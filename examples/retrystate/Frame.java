package retrystate;

/**
 * NEGATIVE CONTROL for the downgrade: a machine with EXACTLY ONE nested
 * production, and it is not self-composing.
 *
 * <p>{@code Plain.wrap} builds {@code new Boxed(other)} out of a {@code Frame} it
 * was handed — neither the current state (so the predecessor-pointer exclusion
 * does not apply) nor a part of it (so it is not structural recursion). One
 * production on one member is not evidence about the TYPE, so the hierarchy is
 * accepted rather than deleted.
 *
 * <p>What must NOT happen next is the edge being published as a resolved
 * transition {@code Plain --> Boxed}. A composed node's relationship to the
 * current state is containment, and the analysis has not established succession.
 * It is recorded as UNRESOLVED with a note, and counted in a diagnostic — the
 * record-everything invariant applied to the one case the bounded veto lets
 * through. The other two members are ordinary and must still resolve, which is
 * what makes this a downgrade rather than the old whole-hierarchy loss.
 */
public sealed interface Frame permits Plain, Boxed, Torn {

    Frame wrap(Frame other);
}
