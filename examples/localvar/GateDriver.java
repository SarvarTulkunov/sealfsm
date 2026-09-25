package examples.localvar;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@link GateMachine#transition} a transition rather
 * than a conversion. Unseeded on purpose, so the initial-state heuristics see
 * nothing new.
 */
final class GateDriver {
    private Gate gate;

    GateDriver(Gate start) {
        this.gate = start;
    }

    void push(Event event) {
        gate = GateMachine.transition(gate, event);
    }
}
