package opaquesuccessor;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@link LatchMachine#next} a transition rather than a
 * conversion. Without it this Tier 2 fixture would be a provisional candidate.
 * Its point is that the commit IS proven and every target is unknown, and a
 * candidate claims neither. Unseeded on purpose.
 */
final class LatchDriver {
    private final LatchMachine machine = new LatchMachine();
    private Latch latch;

    LatchDriver(Latch start) {
        this.latch = start;
    }

    void signal(Signal signal) {
        latch = machine.next(latch, signal);
    }
}
