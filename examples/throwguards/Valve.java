package throwguards;

/**
 * Fixture for finding F14 — <em>abrupt completion</em> in the fall-through
 * guard.
 *
 * <p>{@code if (bad) throw ...; yield new A();} has no {@code else}, yet the
 * producer below the {@code if} is reached only when {@code bad} was false.
 * The extractor already threads that negation across siblings, but it decided
 * "does this branch fall through?" from {@code return} and {@code yield}
 * alone — so a branch that leaves by <em>throwing</em> was read as falling
 * through, and the emitted guard was weaker than the code. Guards feed the
 * SCXML {@code cond} attribute and the nondeterminism analysis, so the loss
 * was silent and sat in a dimension the thesis reports on.
 *
 * <p>{@link ValveController} holds the three shapes that terminate abruptly
 * and the three that only look like they do. See that class for what each arm
 * pins.
 */
public sealed interface Valve permits Charged, Venting, Faulted, Bleeding, Latched, Purging {
}
