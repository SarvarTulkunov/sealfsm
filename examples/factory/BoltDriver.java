package examples.factory;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@link BoltMachine#transition} a transition rather
 * than a conversion. Its helpers are installed through it, because their values
 * are what it returns. Unseeded on purpose, so the initial-state heuristics see
 * nothing new.
 */
final class BoltDriver {
    private Bolt bolt;

    BoltDriver(Bolt start) {
        this.bolt = start;
    }

    void turn(Event event) {
        bolt = BoltMachine.transition(bolt, event);
    }
}
