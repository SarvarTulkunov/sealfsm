package gofstate;

/**
 * F33 NEGATIVE CONTROL for "the holder is outside the hierarchy". Every other
 * requirement is met. Two members each install a hierarchy value naming a state
 * other than their own, once through a structural mutator ({@code setLeft}) and
 * once through a root-typed field write. But the slot written is a member's own
 * field, so this is a tree editing itself, not a context changing state. No
 * construction nests another, so the compositional veto does not fire, and this
 * clause is the only thing rejecting it. Must yield no machine.
 */
public sealed interface Node permits Leaf, Branch {
}
