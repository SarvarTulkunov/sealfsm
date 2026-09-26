package eventsplit;

/**
 * F38 FIXTURE, the dead branch. Σ = {Hit}: {@code strike instanceof Hit} holds
 * for EVERY input, so the else-branch of the ternary is reached only by a null
 * strike, which is not an input. It contributes no edge, and it is COUNTED in a
 * diagnostic rather than vanishing: a branch the source contains and the model
 * omits must be accounted for.
 *
 * <p>Expected: 2 states, 2 transitions ({@code Quiet --Hit--> Ringing},
 * {@code Ringing --> Quiet}), 2/2 resolved, one dead branch reported.
 */
public sealed interface Bell permits Bell.Quiet, Bell.Ringing {
    record Quiet() implements Bell {}
    record Ringing() implements Bell {}
}

sealed interface Strike permits Hit {}

final class Hit implements Strike {}

final class BellMachine {
    static Bell next(Bell current, Strike strike) {
        return switch (current) {
            case Bell.Quiet q -> strike instanceof Hit ? new Bell.Ringing() : q;
            case Bell.Ringing r -> new Bell.Quiet();
        };
    }
}

/** The store-back (F36), unseeded. */
final class BellRope {
    private Bell bell;

    BellRope(Bell start) {
        this.bell = start;
    }

    void pull(Strike strike) {
        bell = BellMachine.next(bell, strike);
    }
}
