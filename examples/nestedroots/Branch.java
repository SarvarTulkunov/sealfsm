package nestedroots;

/**
 * The nested sealed hierarchy that must stay withheld.
 *
 * <p>{@link #replaceChild} returns {@code Branch}, a type inside
 * {@code {Branch, Pair, Wrap}}, so judged on its own this hierarchy is accepted
 * by the distributed recognizer — which is exactly why {@link Node}'s veto has
 * to keep it off the worklist.
 */
public sealed interface Branch extends Node permits Pair, Wrap {

    /**
     * Rebuild this branch with {@code child} spliced in. A tree edit: the result
     * is a bigger node built around a {@code Node}, not a successor of this one.
     *
     * <p>The child arrives as a parameter rather than as {@code this.left}
     * deliberately. Spoon models the receiver of a field read as a
     * {@code CtThisAccess}, which the nesting test counts as a hierarchy value
     * unconditionally (so {@code return this;} reads as a self-loop) — and that
     * would make the composition visible in <em>any</em> hierarchy set, including
     * the narrowed one, so the control would pass without testing anything. A
     * parameter read carries only its declared type, {@code Node}, which is in
     * {@code Node}'s hierarchy and not in {@code Branch}'s. That difference is
     * the whole point of the control.
     */
    Branch replaceChild(Node child);
}
