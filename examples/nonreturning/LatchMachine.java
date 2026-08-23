package nonreturning;

import java.util.Objects;

/**
 * F9 fixture and its two negative controls.
 *
 * <p>Every producer below returns the hierarchy type and every one of them
 * defeats the shallow {@code collectReturns} walk, which does not descend into a
 * {@code switch}. All three therefore look identical to the summariser — an empty
 * return-summary — and only the REASON the summary is empty separates them:
 *
 * <ul>
 *   <li>{@link #reject} contains no {@code return} at all, so the compiler has
 *       already proven it cannot complete normally (JLS §8.4.7). The arms calling
 *       it are undefined inputs and must contribute NO edge. This is F9, and its
 *       exactness is the only licence to suppress.</li>
 *   <li>{@link #escalate} hides a {@code return} inside a {@code switch}, so it
 *       can return a state this analysis cannot see. The arm calling it must stay
 *       UNRESOLVED — dropping it would be the false-drop F9 must not cause.</li>
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

    private LatchMachine() {
    }

    public static Latch step(Latch current, Signal signal) {
        return switch (current) {
            case Idle i -> switch (signal) {
                case ARM -> new Armed();
                case ESCALATE -> escalate(current);
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

    /** Returns a state, but only from inside a switch the summariser cannot read. */
    private static Latch escalate(Latch current) {
        switch (current) {
            case Idle i:
                return new Fired();
            default:
                return new Armed();
        }
    }
}
