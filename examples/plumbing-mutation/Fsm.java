package plumbingmutation;

/**
 * Local opt-in marker, matched by the classifier on its <em>simple name</em>
 * (so the example carries no dependency on the tool). Needed for the same reason
 * as {@code examples/gofcontext}: this hierarchy's transitions live in field
 * mutations, not return values, so the structural classifier does not recognise
 * it automatically.
 */
public @interface Fsm {}
