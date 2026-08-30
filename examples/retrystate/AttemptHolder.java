package retrystate;

/**
 * Seeds the initial state (a hierarchy-typed field with a concrete initialiser)
 * and nothing else. {@code apply} returns void and discriminates nothing, so it
 * is not a transition producer and does not change the machine's encoding.
 */
public final class AttemptHolder {

    private Attempt state = new Ready();

    public void apply(Signal signal) {
        state = state.on(signal);
    }

    public Attempt state() {
        return state;
    }
}
