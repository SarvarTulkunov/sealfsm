package namecollision;

/**
 * CENTRALIZED_DISPATCH / VALUE_RETURN over a hierarchy with colliding simple
 * names.
 *
 * <p>The two {@code UPGRADE} arms are the point of the fixture: they encode
 * {@code Idle -> Legacy.Idle} and {@code Legacy.Idle -> Idle}, two different
 * edges between two different pairs of states. Under simple-name ids both read
 * {@code (Idle, Idle, UPGRADE)} — equal as {@code Transition}s — and the
 * extractor's {@code LinkedHashSet} keeps one. The same holds one level down for
 * {@code Phase.IDLE} and {@code Mode.IDLE}, which are named as targets from
 * distinct sources.
 */
public final class LinkMachine {

    private LinkMachine() {
    }

    public static Link next(Link current, Signal signal) {
        return switch (current) {
            case Idle i -> switch (signal) {
                case OPEN    -> Phase.ACTIVE;
                case UPGRADE -> new Legacy.Idle();   // Idle -> Legacy.Idle
                case RESET   -> Mode.IDLE;           // target collides with Phase.IDLE
            };
            case Legacy.Idle l -> switch (signal) {
                case OPEN    -> Phase.IDLE;          // target collides with Mode.IDLE
                case UPGRADE -> new Idle();          // Legacy.Idle -> Idle
                case RESET   -> Mode.BULK;
            };
            case Phase p -> switch (signal) {
                case OPEN    -> Phase.ACTIVE;
                case UPGRADE -> new Legacy.Idle();
                case RESET   -> new Idle();
            };
            case Mode m -> switch (signal) {
                case OPEN    -> Phase.IDLE;
                case UPGRADE -> Mode.BULK;
                case RESET   -> new Idle();
            };
        };
    }
}
