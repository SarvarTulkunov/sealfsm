package retrystate;

/**
 * One switch over {@link Poll}, committed by FIELD MUTATION — the shape only
 * {@code DispatchCommitDetector} recognises.
 *
 * <p>The commit form is load-bearing for this control, not decoration. {@code
 * handle} returns {@code void}, so the signature-based centralized recognizer
 * (which needs a hierarchy value OUT) never sees it, and no member of
 * {@link Poll} declares a transition method, so the distributed recognizer sees
 * nothing either. This dispatch is the hierarchy's ONLY producer. Written instead
 * as {@code static Poll next(Poll, Tick)} the machine survives the nesting check
 * either way, because the signature recognizer accepts it independently — and the
 * control would then pass while testing nothing.
 */
public final class PollDriver {

    /** Also seeds the initial state: an H-typed field with a concrete initialiser. */
    private Poll state = new Fresh();

    public void handle(Tick tick) {
        state = switch (state) {
            // `f` is the arm's binding: the state being succeeded, spelled the way
            // a centralized dispatch spells it.
            case Fresh f -> tick == Tick.MISS ? new Waiting(f, 1) : f;
            case Waiting w -> switch (tick) {
                // `w.misses() + 1` is an int computed off the current state.
                case MISS -> new Waiting(w, w.misses() + 1);
                case HIT -> new Fresh();
                case EXPIRE -> new Stale(w);
            };
            case Stale s -> tick == Tick.HIT ? new Fresh() : s;
        };
    }

    public Poll state() {
        return state;
    }
}
