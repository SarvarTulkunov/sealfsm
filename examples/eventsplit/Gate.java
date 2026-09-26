package eventsplit;

/**
 * F38 NEGATIVE CONTROL for "exactly one input": two closed event parameters.
 * "key is not TURN" says nothing about {@code mode}, so an edge labelled with a
 * single {@code Key} constant would claim a transition fires whatever the mode
 * is, which the source does not say. No expansion; the test stays a guard.
 *
 * <p>Expected: 2 states, 4 transitions, 4/4 resolved, NO event labels.
 */
public sealed interface Gate permits Gate.Barred, Gate.Free {
    record Barred() implements Gate {}
    record Free() implements Gate {}
}

enum Key { TURN, PULL }

enum Mode { DAY, NIGHT }

final class GateMachine {
    static Gate next(Gate current, Key key, Mode mode) {
        return switch (current) {
            case Gate.Barred b -> key == Key.TURN ? new Gate.Free() : b;
            case Gate.Free f -> key == Key.PULL ? new Gate.Barred() : f;
        };
    }
}

/** The store-back (F36), unseeded. */
final class GatePanel {
    private Gate gate;

    GatePanel(Gate start) {
        this.gate = start;
    }

    void use(Key key, Mode mode) {
        gate = GateMachine.next(gate, key, mode);
    }
}
