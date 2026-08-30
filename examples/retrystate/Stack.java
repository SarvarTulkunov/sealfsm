package retrystate;

/**
 * The single recursive member. A class rather than a record so the veto is the
 * only thing that can reject the hierarchy: a {@code Layer}-typed record component
 * synthesises an accessor the distributed recognizer reads as a second transition
 * method, and the control would then be testing that instead.
 */
public final class Stack implements Layer {

    private final Layer under;
    private final int depth;

    public Stack(Layer under, int depth) {
        this.under = under;
        this.depth = depth;
    }

    @Override
    public Layer flatten() {
        // SELF-COMPOSING: `under` is a part of THIS node, taken apart and
        // reassembled into a new node of the same type. One such production
        // vetoes the hierarchy on its own.
        return new Stack(under.flatten(), depth);
    }
}
