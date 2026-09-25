package converters;

/**
 * F36 MISSING-CALLER CONTROL, switch spelling (thesis Decision 4): the same code as
 * {@link Shade} and {@link Tint}, and nothing in the source set calls it.
 *
 * <p>Missing caller evidence is uncertainty, not proof of a conversion. A
 * library's public transition function looks like this, and so does a converter
 * nobody has called yet. So it is neither a machine nor a rejection: a PROVISIONAL
 * candidate, because a switch over the hierarchy is a plausible dispatch.
 */
public sealed interface Hue permits Hue.Red, Hue.Cyan {
    record Red(int level) implements Hue {}
    record Cyan(int level) implements Hue {}
}

final class Hues {
    static Hue invert(Hue h) {
        return switch (h) {
            case Hue.Red r -> new Hue.Cyan(100 - r.level());
            case Hue.Cyan c -> new Hue.Red(100 - c.level());
        };
    }
}
