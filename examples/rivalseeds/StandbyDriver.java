package rivalseeds;

/**
 * The other driver over {@link Sluice}, seeded FLOWING — a standby line is
 * brought up already carrying water. Neither seed is privileged, which is the
 * point: the two are rival candidates and the analysis must decline to choose.
 */
public final class StandbyDriver {

    private Sluice state = new Sluice.Flowing();

    public void apply(Cmd cmd) {
        state = SluiceLogic.next(state, cmd);
    }

    public Sluice state() {
        return state;
    }
}
