package valueforms;

/** FIRING — the active state. */
public final class Firing implements Signal {

    @Override
    public Step on(Tick tick) {
        if (tick == Tick.RESET) return Step.to(Idle.INSTANCE);   // root-typed singleton
        // Computed by a helper OUTSIDE this method: the successor cannot be pinned
        // without leaving it, so the edge must be recorded UNRESOLVED, not guessed.
        return Step.to(Router.pick(tick));
    }
}
