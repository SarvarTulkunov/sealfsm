package retrystate;

public final class Plain implements Frame {

    @Override
    public Frame wrap(Frame other) {
        // NESTED, and not self-composing: `other` is a foreign hierarchy value.
        return new Boxed(other);
    }
}
