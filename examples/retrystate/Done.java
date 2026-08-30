package retrystate;

/** Absorbing: nothing leaves a completed attempt. */
public final class Done implements Attempt {

    @Override
    public Attempt on(Signal signal) {
        return this;
    }
}
