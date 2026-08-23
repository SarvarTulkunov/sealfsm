package nestedroots;

public record Pair(Node left, Node right) implements Branch {

    /** Wraps a {@code Node} in a new node: composition, not succession. */
    @Override public Branch replaceChild(Node child) {
        return new Wrap(child);
    }
}
