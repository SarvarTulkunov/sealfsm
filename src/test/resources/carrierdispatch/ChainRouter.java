package carrierdispatch;

/**
 * The same commit as {@link Router#route}, reached through the OTHER centralized
 * locus: a chain of {@code instanceof} tests rather than a pattern switch.
 *
 * <p>This is the spelling most Java written before pattern-matching switch uses,
 * so a rule that held at the switch and not here would put the difference between
 * two idioms into the model rather than into the source.
 */
public final class ChainRouter {

    public static Hop flip(Latch current, int event) {
        if (current instanceof Open) {
            return new Hop(new Shut(), "close");
        } else if (current instanceof Shut) {
            return new Hop(new Open(), "open");
        }
        return new Hop(current, "stay");
    }
}
