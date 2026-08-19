package examples.nondeterministic;

/**
 * Sealed root for the guard-reasoning micro-benchmark (finding F5).
 *
 * <p>The machine in {@link VendMachine} mutates its state field through two
 * <em>separate, non-terminating</em> guarded assignments in the same arm — a
 * shape that (unlike a return/yield chain) does not serialise into mutually
 * exclusive guards. The extracted edges therefore carry genuinely overlapping
 * guards and a numeric coverage gap, which the guard IR must diagnose:
 *
 * <ul>
 *   <li><b>nondeterminism</b>: {@code coins >= 1} and {@code coins > 0} overlap,
 *       so two transitions leave {@code Idle} at once for {@code coins >= 1};</li>
 *   <li><b>non-exhaustiveness</b>: neither guard covers {@code coins <= 0}, so
 *       that input has no outgoing transition.</li>
 * </ul>
 *
 * Both edges must still be present — the diagnostics never remove an edge.
 */
@Fsm
public sealed interface Vend permits Idle, Vending {}
