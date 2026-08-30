package retrystate;

/** Retries gave out. Also carries what it gave up on. */
public final class Exhausted implements Attempt {

    private final Attempt lastTry;

    public Exhausted(Attempt lastTry) {
        this.lastTry = lastTry;
    }

    @Override
    public Attempt on(Signal signal) {
        if (signal == Signal.START) return new Ready();
        return this;
    }
}
