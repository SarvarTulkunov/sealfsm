package retrystate;

/** Seeds the initial state; produces nothing. */
public final class FrameHolder {

    private Frame state = new Plain();

    public void apply(Frame other) {
        state = state.wrap(other);
    }
}
