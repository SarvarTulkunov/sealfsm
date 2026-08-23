package scopedlocals;

/**
 * Fixture for finding F13 — <em>variable identity</em> in the "is this local
 * reassigned?" test that gates the resolver's single-assignment shortcuts.
 *
 * <p>Java scopes a local to its block, so one method may legally declare the
 * same name several times in disjoint blocks — and a big centralized dispatch
 * is exactly where that happens, because every arm is its own block and every
 * arm wants to call its successor {@code next}. Asking "is {@code next}
 * reassigned?" by scanning the whole method for a write to <em>some</em>
 * variable spelled {@code next} answers a question about the name, not about
 * the variable: one arm's accumulator makes every other arm's local look
 * reassigned.
 *
 * <p>{@link GateDriver} holds three arms that all declare {@code next}, one of
 * which genuinely reassigns it. See that class for what each arm pins.
 */
public sealed interface Gate permits Shut, Ajar, Wedged {
}
