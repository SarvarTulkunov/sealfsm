package foldbinding;

/**
 * F36 store-backs (thesis Decision 4) for this directory's machines, which pin
 * what a folded callee's PARAMETER holds (F25). None was written with a caller,
 * and without one a returned hierarchy value is indistinguishable from a
 * conversion. {@code TwinDriver.step}, whose {@link Duo} has two state slots and is
 * therefore not a carrier, is deliberately not called: it is the ambiguity
 * control. Unseeded.
 */
final class Installers {
    private Ballast ballast;
    private Carry carry;
    private Flow flow;
    private Loop loop;
    private Twin twin;

    void ballast(Ballast spare, Sig event) {
        ballast = BallastDriver.next(ballast, spare, event);
    }

    void carry(Sig event) {
        carry = CarryDriver.step(carry, event).state();
    }

    void flow(Sig event) {
        flow = FlowDriver.next(flow, event);
    }

    void loop(Sig event) {
        loop = LoopDriver.next(loop, event);
    }

    void twin(Sig event) {
        twin = TwinDriver.hop(twin, event).state();
    }
}
