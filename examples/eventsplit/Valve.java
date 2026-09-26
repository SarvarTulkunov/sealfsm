package eventsplit;

/**
 * F38 FIXTURE, the enum spelling and the two shapes a bare test does not have.
 * Σ = {OPEN, SHUT, BLEED} is an enum, closed like a permits clause. The int
 * {@code pressure} parameter is data, not a second input.
 *
 * <ul>
 *   <li>{@code Shut}: the event test CONJOINED with a data condition. The
 *       then-branch is {@code Shut --OPEN [pressure < 10]--> Open}. The else-branch
 *       is taken on OPEN when the data condition fails and on every other input
 *       unconditionally: {@code Shut --OPEN [!(pressure < 10)]--> Shut},
 *       {@code Shut --SHUT--> Shut}, {@code Shut --BLEED--> Shut}.</li>
 *   <li>{@code Open}: a {@code !=} test, the complement written directly.
 *       {@code Open --OPEN--> Open}, {@code Open --BLEED--> Open},
 *       {@code Open --SHUT--> Shut}.</li>
 * </ul>
 *
 * <p>Expected: 2 states, 7 transitions, 7/7 resolved.
 */
public sealed interface Valve permits Valve.Shut, Valve.Open {
    record Shut() implements Valve {}
    record Open() implements Valve {}
}

enum Knob { OPEN, SHUT, BLEED }

final class ValveMachine {
    static Valve next(Valve current, Knob knob, int pressure) {
        return switch (current) {
            case Valve.Shut s -> knob == Knob.OPEN && pressure < 10 ? new Valve.Open() : s;
            case Valve.Open o -> knob != Knob.SHUT ? o : new Valve.Shut();
        };
    }
}

/** The store-back (F36), unseeded. */
final class ValvePanel {
    private Valve valve;

    ValvePanel(Valve start) {
        this.valve = start;
    }

    void turn(Knob knob, int pressure) {
        valve = ValveMachine.next(valve, knob, pressure);
    }
}
