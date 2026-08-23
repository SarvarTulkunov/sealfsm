package nestedroots;

public record Wrap(Node inner) implements Branch {

    /** Same tree edit from the other side: a bigger node built around a {@code Node}. */
    @Override public Branch replaceChild(Node child) {
        return new Pair(child, child);
    }
}
