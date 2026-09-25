package statelevels;

/**
 * An OVERLAP. {@code Blink} is permitted by both {@code Red} and {@code Amber}, so
 * it is reachable under two direct branches of {@code Signal}. It is one atomic
 * state, and which branch a blinking signal "belongs to" is not a question the
 * type system answers. The tool must not guess one: the overlap is reported, and
 * {@code Blink} is counted once.
 */
public sealed interface Signal permits Red, Amber {
}

sealed interface Red extends Signal permits Blink, Solid {
}

sealed interface Amber extends Signal permits Blink {
}

record Blink() implements Red, Amber {
}

record Solid() implements Red {
}

final class SignalMachine {
    static Signal next(Signal s) {
        return switch (s) {
            case Blink b -> new Solid();
            case Solid o -> new Blink();
        };
    }
}

/** The store-back (F36), unseeded. */
final class SignalDriver {
    private Signal signal;

    SignalDriver(Signal start) {
        this.signal = start;
    }

    void cycle() {
        signal = SignalMachine.next(signal);
    }
}
