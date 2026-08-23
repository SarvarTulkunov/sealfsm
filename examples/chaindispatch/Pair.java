package chaindispatch;

/**
 * Deliberately NOT a record: a {@code record Pair(Tree left, Tree right)} would
 * synthesise two accessors returning the hierarchy type, and the distributed
 * recognizer reads those as per-state transition methods. See {@link Tree}.
 */
public final class Pair implements Tree {

    private final Tree left;
    private final Tree right;

    public Pair(Tree left, Tree right) {
        this.left = left;
        this.right = right;
    }
}
