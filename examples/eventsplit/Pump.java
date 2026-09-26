package eventsplit;

/**
 * F38 NEGATIVE CONTROL, the load-bearing one: {@link Latch}'s ternary, over an
 * OPEN event type. {@code Signal} is a plain interface, so "not Start" is not a
 * finite set: any class anywhere may implement it. There is no Σ to take the
 * complement in, and expanding here would invent a closed world the source does
 * not declare. The edges stay as they were: eventless, the test kept as a guard.
 *
 * <p>Expected: 2 states, 4 transitions, 4/4 resolved, NO event labels.
 */
public sealed interface Pump permits Pump.Off, Pump.On {
    record Off() implements Pump {}
    record On() implements Pump {}
}

interface Signal {}

final class Start implements Signal {}

final class Halt implements Signal {}

final class PumpMachine {
    static Pump next(Pump current, Signal signal) {
        return switch (current) {
            case Pump.Off off -> signal instanceof Start ? new Pump.On() : off;
            case Pump.On on -> signal instanceof Halt ? new Pump.Off() : on;
        };
    }
}

/** The store-back (F36), unseeded. */
final class PumpPanel {
    private Pump pump;

    PumpPanel(Pump start) {
        this.pump = start;
    }

    void send(Signal signal) {
        pump = PumpMachine.next(pump, signal);
    }
}
