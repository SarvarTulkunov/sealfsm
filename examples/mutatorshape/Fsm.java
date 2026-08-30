package mutatorshape;

/**
 * Local opt-in marker, matched by the classifier on its <em>simple name</em> (so
 * the example carries no dependency on the tool). Needed for the same reason as
 * {@code examples/plumbing-mutation}: both hierarchies here commit through field
 * mutation, so nothing returns the hierarchy type and the structural classifier
 * has no producer to recognise.
 */
public @interface Fsm {}
