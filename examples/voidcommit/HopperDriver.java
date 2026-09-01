package voidcommit;

/**
 * A stateful driver whose dispatch commits one call away, into a {@code void}
 * callee.
 *
 * <p>{@link #step} is the discrimination: a {@code switch} <b>statement</b>
 * exhaustive over {@code Hopper}, each arm a bare call. {@link #install} is the
 * commit: it writes the root-typed state field, which is the side effect that
 * survives the caller discarding the call's value, and is therefore the only
 * thing that can prove a commit at this shape.
 *
 * <p>{@code install} deliberately takes TWO parameters and computes what it
 * installs from neither of them alone, so it is not a
 * {@link io.sealfsm.detect.dispatch.MutatorRecognizer recognised mutator} — a
 * mutator commits what it was <em>handed</em>, and this does not. That keeps the
 * fixture on the probe's path rather than on the {@code MUTATOR_ARGUMENT} path
 * that already existed, which is the whole point of writing it.
 *
 * <p>The successor is an arithmetic index into an array, which the resolver
 * cannot follow. It is not obfuscation for its own sake: the fixture asserts
 * {@code 0} resolved, and a successor the resolver could reach would make that
 * assertion test nothing.
 */
public final class HopperDriver {

    private static final Hopper[] WHEEL = { new Empty(), new Filling(), new Full(), new Jammed() };

    private Hopper state = new Empty();
    private int ticks;

    /** The dispatch: exhaustive, and every arm a bare call whose value is discarded. */
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
     * The commit, one call away: a write to the root-typed state field. What it
     * writes is an arithmetic index into {@link #WHEEL}, so the commit is provable
     * and the successor is not.
     */
    private void install(Hopper current, Pulse pulse) {
        this.state = WHEEL[(ticks + pulse.ordinal() + current.hashCode()) % WHEEL.length];
    }

    public Hopper state() {
        return state;
    }
}
