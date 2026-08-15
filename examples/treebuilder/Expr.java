package treebuilder;

/**
 * NEGATIVE CONTROL for the carrier-based recognizer (finding F8).
 *
 * <p>This hierarchy is shaped <em>exactly</em> like the carrier encoding the
 * widened recognizer is meant to accept: every permitted subtype overrides one
 * consistently-named instance method, that method returns a non-hierarchy
 * carrier object ({@link Rewrite}), and the successor {@code Expr} values are
 * arguments to the carrier's factory. Name-based or "returns something wrapping
 * H" detection accepts it.
 *
 * <p>It is nevertheless <b>not a state machine</b>. The {@code Expr} values it
 * builds are not successors of the current node — they are <em>children</em> of
 * a bigger {@code Expr}: {@code Add.simplify()} constructs a new {@code Add}
 * whose operands are the simplified operands. That is composition (a recursive
 * tree rewrite), not succession.
 *
 * <p>The sibling-vs-nested predicate is what separates the two: here the
 * constructed hierarchy value takes other hierarchy values as constructor
 * arguments, so it is NESTED and the whole hierarchy is vetoed. In
 * {@code examples/tcp} the constructed value is handed straight to the carrier
 * with no hierarchy value inside it, so it is a PEER and the hierarchy is
 * accepted.
 */
public sealed interface Expr permits Lit, Neg, Add {

    /** Rewrite this node, reporting the rewritten tree plus its node count. */
    Rewrite simplify();
}
