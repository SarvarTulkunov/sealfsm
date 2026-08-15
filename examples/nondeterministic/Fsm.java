package examples.nondeterministic;

/**
 * Local opt-in marker, matched by the classifier on its <em>simple name</em>
 * (so the example carries no dependency on the tool). The machine drives
 * transitions by mutating a state field, so — like {@code examples/gofcontext} —
 * the structural classifier does not recognise it automatically.
 */
public @interface Fsm {}
