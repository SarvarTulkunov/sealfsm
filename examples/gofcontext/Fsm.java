package examples.gofcontext;

/**
 * Local opt-in marker, matched by the classifier on its <em>simple name</em>
 * (so the example carries no dependency on the tool). It marks the GoF/mutation
 * hierarchy as a state machine: its transitions live in field mutations, not in
 * return values, so the structural classifier does not recognise it
 * automatically — recovering such hierarchies structurally is a deliberate
 * follow-up to finding F2.
 */
public @interface Fsm {}
