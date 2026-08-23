package guardforms;

/**
 * GUARD FORMS — the axis orthogonal to encoding, successor form and commit form:
 * how a {@code when} clause is <em>spelled</em>.
 *
 * <p>The hierarchy is deliberately trivial (two states, one dispatch) so the only
 * thing varying across the twenty arms of {@link SignalMachine} is the shape of
 * the guard expression. Every one of them must reach the edge label; a guard that
 * is silently lost turns a conditional edge into an unconditional claim, and pairs
 * it with a fall-through arm the F12 exclusion can then no longer separate.
 */
public sealed interface Signal permits Idle, Busy {
}
