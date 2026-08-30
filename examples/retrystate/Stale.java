package retrystate;

public final class Stale implements Poll {

    private final Poll previous;

    public Stale(Poll previous) {
        this.previous = previous;
    }
}
