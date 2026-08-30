package retrystate;

public final class Boxed implements Frame {

    private final Frame inner;

    public Boxed(Frame inner) {
        this.inner = inner;
    }

    @Override
    public Frame wrap(Frame other) {
        return new Torn();
    }
}
