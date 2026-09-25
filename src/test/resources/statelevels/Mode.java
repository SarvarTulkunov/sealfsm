package statelevels;

/**
 * A nested sealed branch: {@code On} is a direct branch of {@code Mode} and a
 * grouping node whose own permitted types are the atomic states beneath it.
 *
 * <p>Direct branches {@code {Off, On}}. Atomic states {@code {Off, Low, High}}.
 * Grouping node {@code On}. The exported machine keeps the expansion. The
 * direct-branch count does not absorb it.
 */
public sealed interface Mode permits Off, On {
}

record Off() implements Mode {
}

sealed interface On extends Mode permits Low, High {
}

record Low() implements On {
}

record High() implements On {
}

final class ModeMachine {
    static Mode next(Mode m) {
        return switch (m) {
            case Off o -> new Low();
            case Low l -> new High();
            case High h -> new Off();
        };
    }
}

/** The store-back (F36), unseeded. */
final class ModeDriver {
    private Mode mode;

    ModeDriver(Mode start) {
        this.mode = start;
    }

    void press() {
        mode = ModeMachine.next(mode);
    }
}
