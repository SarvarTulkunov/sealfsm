package chaindispatch;

/**
 * NEGATIVE CONTROL for the sibling-vs-nested guard on the chain path — the
 * {@code instanceof} spelling of {@code examples/treebuilder}. {@link TreeFolder}
 * discriminates both permitted subtypes and commits an H-typed field, so it
 * clears the commit requirement; it must still be REJECTED, because
 * {@code new Pair(tree, ...)} builds a bigger {@code Tree} out of the current
 * one. A recursive data type's "next" is a child, not a successor.
 *
 * <p>Unlike {@code treebuilder} this hierarchy is rejected by that guard <b>and
 * by nothing else</b>, which is what makes it a control rather than a
 * coincidence. It deliberately declares no members typed {@code Tree} that any
 * other recognizer could read:
 *
 * <ul>
 *   <li>{@code Pair} is a class with private fields, not a record with
 *       {@code Tree} components. A record component of the hierarchy type
 *       synthesises an accessor returning H, which the DISTRIBUTED recognizer
 *       accepts as a per-state transition method — two of them here — so a
 *       record-shaped {@code Pair} would be classified POLYMORPHIC before the
 *       chain guard was ever consulted, and the control would pass while testing
 *       nothing.</li>
 *   <li>No member declares any method at all, so {@code composesItself} — which
 *       inspects only methods ON the hierarchy — answers false, and the
 *       compositional veto does not fire either. In {@code treebuilder} it does,
 *       via {@code Add.simplify()}, which is why that fixture cannot isolate
 *       this.</li>
 * </ul>
 */
public sealed interface Tree permits Leaf, Pair {
}
