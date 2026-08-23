package rivalseeds;

/** The other driver over {@link Damper} — seeded PARKED as well: the seeds AGREE. */
public final class DamperStandby {

    private Damper state = new Damper.Parked();

    public void apply(Drive drive) {
        state = DamperLogic.next(state, drive);
    }

    public Damper state() {
        return state;
    }
}
