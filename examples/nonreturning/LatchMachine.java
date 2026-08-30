package nonreturning;

import java.util.Objects;

/**
 * F9 fixture and its negative controls.
 *
 * <p>Every producer below returns the hierarchy type and every one of them
 * yields an EMPTY return-summary to a walker that stops at the first construct
 * it does not model. That is what makes them a set: they are indistinguishable
 * by the summary alone, and only the REASON the summary is empty separates them.
 *
 * <ul>
 *   <li>{@link #reject} contains no {@code return} at all, so the compiler has
 *       already proven it cannot complete normally (JLS §8.4.7). The arms calling
 *       it are undefined inputs and must contribute NO edge. This is F9, and its
 *       exactness is the only licence to suppress.</li>
 *   <li>{@link #escalate} hides a {@code return} inside a {@code switch}. It was
 *       the original negative control — a real target the summariser could not
 *       see — and F18 closed that gap: the fold now runs the ordinary walker over
 *       a callee body, so this arm RESOLVES. It stays in the fixture as the
 *       positive case, and because it is also the context-sensitivity control:
 *       {@code escalate} re-switches on the state, and its {@code default:} arm
 *       cannot run when the caller has already matched {@code Idle}. Folding it
 *       without that reasoning publishes a second edge sourced at
 *       {@code <unknown>}, an origin invented out of a context the walk had.</li>
 *   <li>{@link #defer} is the negative control that replaces it, and the standing
 *       probe for the completeness test: its {@code return} sits inside a
 *       {@code synchronized} block, which the walker does not descend. It must
 *       stay UNRESOLVED — recorded, never guessed, never dropped. The point is
 *       not that {@code synchronized} is special; it is that the fold asks what
 *       the walk ACTUALLY reached rather than whether the body matched a list of
 *       constructs someone remembered to extend. Teach the walker about
 *       {@code synchronized} and this assertion fails loudly, which is the
 *       correct outcome: a new probe gets chosen, and the control never quietly
 *       stops testing anything.</li>
 *   <li>{@code Objects.requireNonNull(current)} is a JDK method, so Spoon supplies
 *       a reflective SHADOW declaration whose body is an empty stub. It has no
 *       {@code return} for the same reason it has nothing at all — it was never
 *       parsed. F9 read that emptiness as proof and deleted the edge; F11 requires
 *       the rule to apply only to a body the analysis actually read, so this arm
 *       must stay UNRESOLVED too. It is the same class of hole as
 *       {@code Optional.orElse(new Fired())}.</li>
 * </ul>
 */
public final class LatchMachine {

    private static final Object LOCK = new Object();

    private LatchMachine() {
    }

    public static Latch step(Latch current, Signal signal) {
        return switch (current) {
            case Idle i -> switch (signal) {
                case ARM -> new Armed();
                case ESCALATE -> escalate(current);
                case DEFER -> defer(current);
                // Semantically a self-loop — requireNonNull returns its argument —
                // but the analysis cannot know that without reading the JDK body,
                // so the honest answer is UNRESOLVED, never a suppressed edge.
                case HOLD -> Objects.requireNonNull(current);
                default -> reject(current, signal);
            };
            case Armed a -> switch (signal) {
                case FIRE -> new Fired();
                case RESET -> new Idle();
                default -> reject(current, signal);
            };
            case Fired f -> reject(current, signal);
        };
    }

    /** Never returns: no {@code return} statement anywhere in the body. */
    private static Latch reject(Latch current, Signal signal) {
        throw new IllegalStateException("no transition for " + signal + " in " + current);
    }

    /**
     * Returns a state from inside a {@code switch} — readable, and read. The
     * {@code default:} arm is dead at the only call site, which matches
     * {@code Idle}.
     */
    private static Latch escalate(Latch current) {
        switch (current) {
            case Idle i:
                return new Fired();
            default:
                return new Armed();
        }
    }

    /** Returns a state from inside a construct the walker does not descend. */
    private static Latch defer(Latch current) {
        synchronized (LOCK) {
            return new Armed();
        }
    }
}
