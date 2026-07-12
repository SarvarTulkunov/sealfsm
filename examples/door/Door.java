package examples.door;

/**
 * Centralized encoding. A single transition function pattern-matches over the
 * current state. Exercises: type-pattern from-states, constructor-call targets,
 * a guarded (ternary) transition, and a `return current` self-loop.
 *
 * Expected extraction:
 *   states:       Open, Closed, Locked
 *   transitions:  Closed --[isLock]--> Locked, Closed --[!isLock]--> Open,
 *                 Open --> Closed,
 *                 Locked --[isUnlock]--> Closed, Locked --[!isUnlock]--> Locked (self)
 */
public sealed interface Door permits Open, Closed, Locked {}
