package valueforms;

/** ARMED — ready to fire. */
public final class Armed implements Signal {

    /** Declared as the concrete state, so the declared type alone pins it. */
    public static final Armed INSTANCE = new Armed();

    @Override
    public Step on(Tick tick) {
        if (tick == Tick.FIRE) {
            Signal next = new Firing();     // local holding the successor
            return Step.to(next);
        }
        if (tick == Tick.ADVANCE) return Step.to(Phase.RAMP);   // enum constant
        return Step.stay(this);
    }
}
