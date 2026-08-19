package examples.gofcontext;

/** The (closed) input alphabet driving the portal. */
public sealed interface Event permits Lock, Unlock {}
