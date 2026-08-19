package valueforms;

/**
 * A permitted subtype that is an {@code enum}: its constants are the states, and
 * they are as compiler-checked-exhaustive as a {@code permits} clause.
 */
public enum Phase implements Signal {
    RAMP, PEAK;

    @Override
    public Step on(Tick tick) {
        if (tick == Tick.ADVANCE) return Step.to(PEAK);          // enum constant
        if (tick == Tick.RESET) return Step.to(Idle.INSTANCE);
        return Step.stay(this);
    }
}
