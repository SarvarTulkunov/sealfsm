package retrystate;

public final class Torn implements Frame {

    @Override
    public Frame wrap(Frame other) {
        return this;
    }
}
