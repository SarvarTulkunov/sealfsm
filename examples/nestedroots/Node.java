package nestedroots;

/**
 * NEGATIVE CONTROL for the compositional veto, and the load-bearing one.
 *
 * <p>{@code Node} is a tree: {@link Pair} and {@link Wrap} build bigger nodes out
 * of the {@code Node} values they already hold, so the veto rejects it — a
 * recursive data type, not an automaton. Its nested sealed subtype {@link Branch}
 * must NOT then be re-offered as a root.
 *
 * <p>The reason is that the veto is judged against the hierarchy set of whichever
 * root is being classified, and a child's set is strictly narrower than its
 * parent's. Judged against {@code {Node, Leaf, Branch, Pair, Wrap}},
 * {@code new Wrap(this.left)} nests a hierarchy value inside another one, because
 * {@code this.left} is typed {@code Node}. Judged against
 * {@code {Branch, Pair, Wrap}}, that argument is a foreign type and the same
 * expression reads as an ordinary peer production — the veto evaporates. Since
 * {@code normalize()} returns {@code Branch}, the distributed recognizer would
 * then accept it, and the tree builder would be reported as a state machine one
 * level below the veto that caught it.
 */
public sealed interface Node permits Leaf, Branch {
}
