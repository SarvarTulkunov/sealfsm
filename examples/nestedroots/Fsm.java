package nestedroots;

/**
 * Local opt-in marker, matched by the classifier on its <em>simple name</em> so
 * the example carries no dependency on the tool. Needed for the same reason as
 * in {@code examples/plumbing-mutation}: {@link Body}'s transitions live in
 * field mutations, not return values, so no structural recognizer sees them.
 */
public @interface Fsm {}
