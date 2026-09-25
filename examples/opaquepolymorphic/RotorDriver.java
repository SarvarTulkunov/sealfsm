package opaquepolymorphic;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@code Rotor.advance} a transition rather than a
 * conversion at the POLYMORPHIC_OVERRIDE locus. Without it this Tier 2 fixture
 * would be a provisional candidate. Unseeded on purpose.
 */
final class RotorDriver {
    private Rotor rotor;

    RotorDriver(Rotor start) {
        this.rotor = start;
    }

    void nudge(int delta) {
        rotor = rotor.advance(delta);
    }
}
