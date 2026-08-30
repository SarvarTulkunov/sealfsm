package retrystate;

/** The leaf case: already flat, so peer-shaped in isolation. */
public record Base(int value) implements Layer {

    @Override
    public Layer flatten() {
        return this;
    }
}
