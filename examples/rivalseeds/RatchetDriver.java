package rivalseeds;

/** The one real seed in the {@link Ratchet} hierarchy: the machine starts FREE. */
public final class RatchetDriver {

    private Ratchet state = new Ratchet.Free();

    public void apply(Pawl pawl) {
        state = RatchetLogic.next(state, pawl);
    }

    public Ratchet state() {
        return state;
    }
}
