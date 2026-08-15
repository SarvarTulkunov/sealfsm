package treebuilder;

/**
 * The carrier: a rewritten {@link Expr} plus a cost, structurally identical to
 * {@code tcp.Transition}. Its presence is exactly why the recognizer cannot be
 * satisfied by "a carrier wraps a hierarchy value" alone.
 */
public record Rewrite(Expr result, int nodes) {

    public static Rewrite of(Expr result, int nodes) {
        return new Rewrite(result, nodes);
    }

    public static Rewrite unchanged(Expr result) {
        return new Rewrite(result, 1);
    }
}
