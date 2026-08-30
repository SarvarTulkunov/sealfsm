package hiddenreturns;

/**
 * F18 fixture — the SUMMARISABILITY boundary of the inter-procedural fold (F3).
 *
 * <p>The encoding is held fixed (CENTRALIZED_DISPATCH / VALUE_RETURN, one switch
 * over the state delegating to one helper per state — the shape F3 exists for)
 * and only ONE thing varies: which control-flow construct the helper's
 * {@code return} sits inside. Each helper below is the same function written
 * behind a different statement, so the extracted relation must be the same
 * modulo what the walker can actually read.
 *
 * <ul>
 *   <li>{@link #fromIdle} returns from inside a {@code switch} STATEMENT — the
 *       ordinary pre-arrow spelling of a per-event table, and the case the fold
 *       could not summarise at all.</li>
 *   <li>{@link #fromPriming} returns from inside a {@code try}, and again from
 *       its {@code catch} — the classic error transition, which must arrive
 *       under the exceptional guard rather than as an unconditional edge.</li>
 *   <li>{@link #fromRunning} is the sharp one, and the reason the gap was a
 *       SOUNDNESS bug rather than only a recall gap: one return sits inside a
 *       loop and one after it. A summariser that collects what it can reach and
 *       then treats a non-empty result as complete folds the trailing
 *       {@code new Running()} into a resolved self-loop and DROPS the
 *       {@code Halted} target — a real transition gone with no unresolved
 *       marker, behind a clean-looking n/n. That is the one outcome the
 *       record-everything invariant forbids.</li>
 *   <li>{@link #fromHalted} is the NEGATIVE CONTROL and the standing probe: its
 *       only return sits inside a {@code synchronized} block, which the walker
 *       does not model. It must stay UNRESOLVED. The point is not that
 *       {@code synchronized} is special — it is that the completeness test asks
 *       what the walk ACTUALLY reached, not whether the body matched a list of
 *       constructs someone remembered to extend. If a later change teaches the
 *       walker about {@code synchronized}, this assertion fails loudly and a new
 *       probe is chosen; it does not silently stop testing anything.</li>
 * </ul>
 */
public final class PumpController {

    private static final int RETRY_LIMIT = 3;

    private final Object lock = new Object();
    private Pump state = new Idle();

    public Pump step(Signal event) {
        state = transition(state, event);
        return state;
    }

    public Pump transition(Pump current, Signal event) {
        return switch (current) {
            case Idle i -> fromIdle(event);
            case Priming p -> fromPriming(event);
            case Running r -> fromRunning(event);
            case Halted h -> fromHalted(event);
        };
    }

    /** Every return is inside a switch STATEMENT. */
    private Pump fromIdle(Signal event) {
        switch (event) {
            case START:
                return new Priming();
            case STOP:
                return new Halted();
            default:
                return new Idle();
        }
    }

    /** One return inside the try body, one inside the catch. */
    private Pump fromPriming(Signal event) {
        try {
            requirePressure(event);
            return event == Signal.PRIMED ? new Running() : new Priming();
        } catch (IllegalStateException lost) {
            return new Halted();
        }
    }

    /** One return inside a loop, one after it: the partial-summary trap. */
    private Pump fromRunning(Signal event) {
        for (int attempt = 0; attempt < RETRY_LIMIT; attempt++) {
            if (event == Signal.FAULT) {
                return new Halted();
            }
        }
        return new Running();
    }

    /** NEGATIVE CONTROL: the only return sits in a construct the walker does not model. */
    private Pump fromHalted(Signal event) {
        synchronized (lock) {
            return event == Signal.RESET ? new Idle() : new Halted();
        }
    }

    private void requirePressure(Signal event) {
        if (event == Signal.FAULT) {
            throw new IllegalStateException("pressure lost while priming");
        }
    }
}
