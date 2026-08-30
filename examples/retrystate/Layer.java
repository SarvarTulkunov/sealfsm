package retrystate;

/**
 * NEGATIVE CONTROL for the threshold, and the load-bearing one: a recursive data
 * type with only ONE recursive member.
 *
 * <p>"Require two nested productions across two members before vetoing" bounds the
 * veto, and on its own it opens a hole exactly where recursive sealed types are
 * commonest — the two-member tree, {@code permits Leaf, Node}, whose leaf case is
 * peer-shaped and whose one branch case rebuilds itself. One production, one
 * member: under a bare count threshold it clears the veto, {@code flatten()}
 * returns the hierarchy type on both members so the distributed recognizer accepts
 * it, and a tree is published as an automaton whose single edge is
 * {@code Stack -> Stack}.
 *
 * <p>What closes the hole is the disjunct the count sits beside: a production is
 * SELF-COMPOSING when a surviving nested argument is derived from the current
 * state, and that is structural recursion by definition — sufficient alone, no
 * corroboration needed. {@code new Stack(under.flatten(), depth)} reads
 * {@code this.under}, a PART of the current state, and reassembles a node around
 * it. That is precisely what {@code new Retrying(this, attempts + 1)} does not do,
 * and the whole distinction between the two fixtures is which of the two an
 * argument is.
 *
 * <p>The control bites only because this hierarchy would otherwise be accepted;
 * the test asserts that, so it cannot quietly stop testing anything.
 */
public sealed interface Layer permits Base, Stack {

    /** Returns the hierarchy type on both members — the distributed recognizer's signal. */
    Layer flatten();
}
