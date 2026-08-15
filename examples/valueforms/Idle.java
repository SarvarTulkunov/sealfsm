package valueforms;

/** IDLE — the resting state. Exposes a singleton typed as the abstract root. */
public final class Idle implements Signal {

    /**
     * Declared as {@code Signal}, not {@code Idle}. Reading the declared type says
     * only "some state", so the identity has to come from the initializer; a
     * resolver that stops at the declared type reports a false self-loop here.
     */
    public static final Signal INSTANCE = new Idle();

    @Override
    public Step on(Tick tick) {
        if (tick == Tick.ARM) return Step.to(Armed.INSTANCE);   // singleton (concrete-typed)
        return Step.stay(this);                                  // self-loop, default edge
    }
}
