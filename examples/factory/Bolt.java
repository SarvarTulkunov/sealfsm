package examples.factory;

/**
 * Sealed root for the inter-procedural micro-benchmark (finding F3). The
 * transition function in {@link BoltMachine} does not construct the next state
 * inline; each arm <em>delegates</em> to a helper method, so the target is only
 * recoverable by folding the helper's return values back into the call site.
 */
public sealed interface Bolt permits Open, Shut, Jammed {}
