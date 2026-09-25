package nonreturning;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@link LatchMachine#step} a transition rather than a
 * conversion. Unseeded on purpose, so the initial-state heuristics see nothing
 * new.
 */
final class LatchDriver {
    private Latch latch;

    LatchDriver(Latch start) {
        this.latch = start;
    }

    void signal(Signal signal) {
        latch = LatchMachine.step(latch, signal);
    }
}
