package throwcarrier;

/**
 * The carrier: an ordinary result wrapper holding the next {@link Capstan}.
 *
 * <p>Both factories genuinely return, which is the whole difference between them
 * and {@link Undefined#illegal}.
 */
public record Haul(Capstan next) {

    /** Move to {@code next}. */
    public static Haul to(Capstan next) {
        return new Haul(next);
    }

    /** Stay put — the carrier spelling of a self-loop. */
    public static Haul stay(Capstan current) {
        return new Haul(current);
    }
}
