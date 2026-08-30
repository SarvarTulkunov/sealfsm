package retrystate;

/** Carries the poll it is waiting on, plus how many misses it has seen. */
public final class Waiting implements Poll {

    private final Poll previous;
    private final int misses;

    public Waiting(Poll previous, int misses) {
        this.previous = previous;
        this.misses = misses;
    }

    /** Returns an {@code int}. The RECEIVER is a Poll; the value is not. */
    public int misses() {
        return misses;
    }
}
