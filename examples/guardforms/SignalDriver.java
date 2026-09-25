package guardforms;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@link SignalMachine#step} a transition rather than
 * a conversion. Unseeded on purpose, so the initial-state heuristics see nothing
 * new.
 */
final class SignalDriver {
    private final SignalMachine machine = new SignalMachine();
    private Signal signal;

    SignalDriver(Signal start) {
        this.signal = start;
    }

    void fire(Trigger trigger) {
        signal = machine.step(signal, trigger);
    }
}
