package valueforms;

/**
 * F36 driver (thesis Decision 4): unwraps each carrier and installs its successor
 * as the current state. A {@link Step} returned by {@code Signal.on} is a value
 * that carries a state. Only reading the state out and storing it back separates
 * a transition from a conversion. Unseeded on purpose, so the initial-state
 * heuristics see nothing new.
 */
final class SignalDriver {
    private Signal signal;

    SignalDriver(Signal start) {
        this.signal = start;
    }

    void tick(Tick tick) {
        signal = signal.on(tick).next();
    }
}
