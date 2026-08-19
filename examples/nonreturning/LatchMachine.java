package nonreturning;

/**
 * F9 fixture and its own negative control.
 *
 * <p>Both helpers below return the hierarchy type and both defeat the shallow
 * {@code collectReturns} walk, which does not descend into a {@code switch}.
 * Only one of them can actually produce a state:
 *
 * <ul>
 *   <li>{@link #reject} contains no {@code return} at all, so the compiler has
 *       already proven it cannot complete normally. The arms calling it are
 *       undefined inputs and must contribute NO edge.</li>
 *   <li>{@link #escalate} hides a {@code return} inside a {@code switch}, so it
 *       can return a state this analysis cannot see. The arm calling it must stay
 *       UNRESOLVED — dropping it would be the false-drop F9 must not cause.</li>
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
