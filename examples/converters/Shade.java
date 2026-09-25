package converters;

/**
 * F36 CONVERTER-USE CONTROL, switch spelling (thesis Decision 4).
 *
 * <p>{@link Shades#invert} is a centralized switch over the hierarchy returning
 * the hierarchy. That is the exact shape {@code examples/door} is accepted on, and
 * until F36 it was accepted by its codomain alone (LIMITATIONS.md L3, row 1). Its
 * only caller reads the result as DATA ({@code instanceof}) and never stores it
 * back as a current state, so it is an ESTABLISHED conversion: rejected, with no
 * candidate.
 *
 * <p>{@link Tint} is the same code with a store-back (a machine), and {@link Hue}
 * is the same code with no caller at all (a provisional candidate). The three are
 * written to differ in nothing but what happens to the result.
 */
public sealed interface Shade permits Shade.Light, Shade.Dark {
    record Light(int level) implements Shade {}
    record Dark(int level) implements Shade {}
}

final class Shades {
    static Shade invert(Shade s) {
        return switch (s) {
            case Shade.Light l -> new Shade.Dark(100 - l.level());
            case Shade.Dark d -> new Shade.Light(100 - d.level());
        };
    }

    /** The result is examined and dropped: a value, not a state. */
    static boolean invertsToDark(Shade s) {
        return invert(s) instanceof Shade.Dark;
    }
}
