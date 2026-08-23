package rivalseeds;

/** One of two drivers over {@link Damper}. Seeded PARKED. */
public final class DamperPrimary {

    private Damper state = new Damper.Parked();

    public void apply(Drive drive) {
        state = DamperLogic.next(state, drive);
    }

    public Damper state() {
        return state;
    }
}
