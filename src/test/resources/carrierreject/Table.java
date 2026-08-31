package carrierreject;

/**
 * A centralized transition table committing through a carrier — the cell 3a
 * opened — with F9 (a helper that cannot return normally is not a producer)
 * asked at that new locus.
 *
 * <p>Without F9 here, {@code Undefined.illegal(b, event)} unwraps its
 * hierarchy-typed argument and publishes {@code Busy -> Busy} as a RESOLVED
 * edge: a fabrication wearing a clean score, one per undefined cell.
 */
public final class Table {

    public static Move step(Cell current, int event) {
        return switch (current) {
            case Ready r -> new Move(new Busy(), "start");
            case Busy b -> Undefined.illegal(b, event);
            case Spent s -> Undefined.recover(s, event);
        };
    }
}
