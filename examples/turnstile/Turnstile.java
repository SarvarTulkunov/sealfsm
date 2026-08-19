package examples.turnstile;

/** Canonical two-state FSM. States enumerated exactly from the permits clause. */
public sealed interface Turnstile permits Locked, Unlocked {}
