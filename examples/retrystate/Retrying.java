package retrystate;

/**
 * The predecessor-carrying state. Written as a class, not a record: see
 * {@link Attempt} for why an {@code Attempt}-typed record component would change
 * what this fixture measures.
 */
public final class Retrying implements Attempt {

    private final Attempt previous;
    private final int attempts;

    public Retrying(Attempt previous, int attempts) {
        this.previous = previous;
        this.attempts = attempts;
    }

    @Override
    public Attempt on(Signal signal) {
        if (signal == Signal.SUCCEED) return new Done();
        if (signal == Signal.GIVE_UP) return new Exhausted(this);
        if (signal == Signal.FAIL) {
            if (attempts >= 3) return new Exhausted(this);
            // Both arguments are ordinary: `this` is the state being succeeded,
            // and `attempts + 1` is an int read OFF that state. The second is the
            // receiver case — a payload computed from the current state is not a
            // hierarchy value nested inside anything.
            return new Retrying(this, attempts + 1);
        }
        return this;
    }
}
