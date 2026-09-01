package voidfold;

/**
 * The same driver as {@code voidcommit.HopperDriver}, line for line, except for
 * the single assignment inside {@link #install}.
 *
 * <p>{@link #step} is the identical exhaustive dispatch and the identical bare
 * call per arm. {@link #install} takes the identical two parameters and reads the
 * identical state. It simply writes {@code this.label}, a {@code String}, rather
 * than {@code this.state}.
 *
 * <p>Everything an analysis can see <em>at the dispatch</em> is therefore the
 * same in both fixtures, which is the point: the difference must be found by
 * asking what type the callee installs, and it must be found only there.
 *
 * <p>The {@code Hopper}-typed field is kept, seeded exactly as {@code voidcommit}
 * seeds it, so the two are not accidentally separated by the presence or absence
 * of a state field. It is never written after construction — which is precisely
 * why this is a fold and not a machine.
 */
public final class HopperDriver {

    private static final Hopper[] WHEEL = { new Empty(), new Filling(), new Full(), new Jammed() };

    private Hopper state = new Empty();
    private String label = "";
    private int ticks;

    /** The dispatch: identical to voidcommit's, down to the argument. */
    public void step(Pulse pulse) {
        ticks++;
        switch (state) {
            case Empty e -> install(e, pulse);
            case Filling f -> install(f, pulse);
            case Full f -> install(f, pulse);
            case Jammed j -> install(j, pulse);
        }
    }

    /**
     * The fold. Same shape as {@code voidcommit}'s commit — a field write derived
     * from the matched state — into a field whose declared type is outside the
     * hierarchy. Nothing here installs a successor.
     */
    private void install(Hopper current, Pulse pulse) {
        this.label = WHEEL[(ticks + pulse.ordinal() + current.hashCode()) % WHEEL.length]
                .getClass().getSimpleName();
    }

    public String label() {
        return label;
    }
}
