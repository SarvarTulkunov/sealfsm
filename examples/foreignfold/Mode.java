package examples.foreignfold;

/**
 * NEGATIVE CONTROL for the widened centralized recognizer (Part B, B1).
 *
 * <p>Every structural signal the widened recognizer looks for is present: a
 * sealed hierarchy, a field of the hierarchy type held by a driver class, and
 * exhaustive pattern-matching switches over that field. Only one thing is
 * missing, and it is the only thing that matters — nothing ever commits a
 * {@code Mode} value. The switches fold the hierarchy into a foreign codomain
 * ({@code String}), which is ordinary exhaustive pattern matching, not a
 * transition relation.
 *
 * <p>If this hierarchy were accepted, the tool would report an automaton whose
 * every "state" has zero real transitions — the precise false positive the
 * commit requirement exists to prevent.
 */
public sealed interface Mode permits Fast, Slow, Stopped {
}
