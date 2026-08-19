package examples.accumulator;

/**
 * Centralized dispatch whose result is committed through a hierarchy-typed
 * LOCAL, and whose arms compute the successor by assigning a second local on
 * several branches:
 *
 * <pre>{@code
 *   Phase r;
 *   if (signal == Signal.BLOCK) r = new Blocked();
 *   else                        r = new Working();
 *   yield r;
 * }</pre>
 *
 * <p>This is the shape finding F1 is about, and it is a soundness trap in two
 * directions at once. Reading {@code r}'s DECLARED type yields the sealed root,
 * which the "root-typed read means stay put" rule would turn into a confident,
 * unguarded self-loop — one fabricated edge, and both real targets lost. Equally,
 * collapsing the branch assignments into a single reaching value would drop one
 * of the two real successors. The reaching-definitions pass must return both,
 * each under its own mutually-exclusive guard, and must emit no self-loop that
 * the source does not actually contain.
 *
 * <p>The {@code Blocked} arm delegates three calls deep, past the k = 2
 * inter-procedural budget, so its successor is honestly UNRESOLVED rather than
 * chased or guessed.
 */
public final class PhaseDriver {

    private Phase phase = new Ready();

    public Phase phase() {
        return phase;
    }

    public Phase advance(Signal signal) {
        Phase next = switch (phase) {
            case Ready() -> {
                // Branch-assigned accumulator: two distinct targets, no self-loop.
                Phase r;
                if (signal == Signal.START) {
                    r = new Working();
                } else {
                    r = new Blocked();
                }
                yield r;
            }
            case Working() -> {
                // Initialised then conditionally overwritten: the initializer
                // survives on the negated path, the overwrite on the positive one.
                Phase r = new Working();
                if (signal == Signal.BLOCK) {
                    r = new Blocked();
                }
                yield r;
            }
            case Blocked() -> recover(signal);
            case Halted() -> throw new IllegalStateException("halted");
        };
        phase = next;
        return next;
    }

    // ---- an out-of-budget delegation chain: the successor stays UNRESOLVED ----

    private Phase recover(Signal signal) {
        return recoverInner(signal);
    }

    private Phase recoverInner(Signal signal) {
        return recoverDeep(signal);
    }

    private Phase recoverDeep(Signal signal) {
        return signal == Signal.STOP ? new Halted() : new Ready();
    }
}
