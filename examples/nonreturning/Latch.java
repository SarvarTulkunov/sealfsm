package nonreturning;

/** A three-state latch; the transition function lives in {@link LatchMachine}. */
public sealed interface Latch permits Idle, Armed, Fired {
}
