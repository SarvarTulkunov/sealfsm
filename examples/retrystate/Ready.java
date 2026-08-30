package retrystate;

/** The start state. Its successor is the first attempt, which remembers it. */
public final class Ready implements Attempt {

    @Override
    public Attempt on(Signal signal) {
        // `this` is the CURRENT STATE handed to the successor's constructor — a
        // predecessor pointer, not a child node. Under the unbounded veto this
        // single expression rejected the whole hierarchy.
        if (signal == Signal.START) return new Retrying(this, 1);
        return this;
    }
}
