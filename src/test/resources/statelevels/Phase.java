package statelevels;

/**
 * Thesis Decision 2's own example, verbatim: a permitted enum is a grouping node,
 * and its constants are the atomic states.
 *
 * <p>Direct branches {@code {Idle, Speed}}. Atomic states
 * {@code {Idle, Speed.SLOW, Speed.FAST}}. {@code Speed} is a grouping node that
 * is not counted a second time as an atomic state.
 */
public sealed interface Phase permits Idle, Speed {
}

record Idle() implements Phase {
}

enum Speed implements Phase {
    SLOW, FAST
}

final class PhaseMachine {
    static Phase next(Phase p) {
        return switch (p) {
            case Idle i -> Speed.SLOW;
            case Speed s when s == Speed.SLOW -> Speed.FAST;
            case Speed s -> new Idle();
        };
    }
}

/** The store-back (F36), unseeded. */
final class PhaseDriver {
    private Phase phase;

    PhaseDriver(Phase start) {
        this.phase = start;
    }

    void tick() {
        phase = PhaseMachine.next(phase);
    }
}
