package converters;

/**
 * F36 DEMONSTRATED STATE UPDATE, switch spelling (thesis Decision 4): the same
 * code as {@link Shade}, with a caller that stores the result back into the field
 * it read the state from ({@link Lens#flip}). That store-back is the evidence a
 * returned hierarchy value becomes the current state, so this one is a machine:
 * 2 direct branches, 2 atomic states, 2/2, commit evidence VIA_CALLER.
 */
public sealed interface Tint permits Tint.Warm, Tint.Cool {
    record Warm(int level) implements Tint {}
    record Cool(int level) implements Tint {}
}

final class Tints {
    static Tint invert(Tint t) {
        return switch (t) {
            case Tint.Warm w -> new Tint.Cool(100 - w.level());
            case Tint.Cool c -> new Tint.Warm(100 - c.level());
        };
    }
}

/** Unseeded, so the initial-state heuristics are handed nothing. */
final class Lens {
    private Tint tint;

    Lens(Tint start) {
        this.tint = start;
    }

    void flip() {
        tint = Tints.invert(tint);
    }
}
