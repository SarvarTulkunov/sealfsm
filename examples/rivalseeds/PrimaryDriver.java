package rivalseeds;

/** One of two drivers over {@link Sluice}. Seeded SHUT. */
public final class PrimaryDriver {

    private Sluice state = new Sluice.Shut();

    public void apply(Cmd cmd) {
        state = SluiceLogic.next(state, cmd);
    }

    public Sluice state() {
        return state;
    }
}
