package eventsplit;

/**
 * F38 FIXTURE, the positive case: a CLOSED sealed event alphabet
 * ({@code Cmd permits Arm, Fire, Reset}). An event test partitions it, so the
 * branch taken when the test FAILS fires on the remaining inputs, each one its
 * own transition, and never as one eventless edge guarded by the negated test.
 *
 * <p>Three spellings on one dispatch, so no difference of file or host can stand
 * in for the rule:
 * <ul>
 *   <li>{@code Idle}: a ternary. {@code Idle --Arm--> Armed}, and the else-branch
 *       {@code Idle --Fire--> Idle}, {@code Idle --Reset--> Idle}.</li>
 *   <li>{@code Armed}: an {@code if} that leaves, twice, then a fall-through.
 *       {@code Armed --Fire--> Fired}, {@code Armed --Reset--> Idle}, and the
 *       fall-through only on what is left: {@code Armed --Arm--> Armed}.</li>
 *   <li>{@code Fired}: an arm that never tests the input. It stays ONE eventless
 *       edge, {@code Fired --> Idle} ("whatever the input"): the source did not
 *       partition Σ there, so nothing licenses one edge per input.</li>
 * </ul>
 *
 * <p>Expected: 3 states, 7 transitions, 7/7 resolved.
 */
public sealed interface Latch permits Latch.Idle, Latch.Armed, Latch.Fired {
    record Idle() implements Latch {}
    record Armed() implements Latch {}
    record Fired() implements Latch {}
}

final class LatchMachine {
    static Latch next(Latch current, Cmd cmd) {
        return switch (current) {
            case Latch.Idle i -> cmd instanceof Arm ? new Latch.Armed() : i;
            case Latch.Armed a -> {
                if (cmd instanceof Fire) yield new Latch.Fired();
                if (cmd instanceof Reset) yield new Latch.Idle();
                yield a;
            }
            case Latch.Fired f -> new Latch.Idle();
        };
    }
}

/** The store-back (F36). Unseeded, so no initial-state heuristic is handed a seed. */
final class LatchPanel {
    private Latch latch;

    LatchPanel(Latch start) {
        this.latch = start;
    }

    void press(Cmd cmd) {
        latch = LatchMachine.next(latch, cmd);
    }
}
