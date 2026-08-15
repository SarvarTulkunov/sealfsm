package examples.nondeterministic;

/**
 * Mutation-style transition code with overlapping, non-exhaustive guards
 * (finding F5). The two {@code if}s in the {@code Idle} arm each assign the
 * state field but neither <em>terminates</em> the arm, so the walker does not
 * serialise them into exclusive guards: both edges are emitted with their raw
 * conditions.
 *
 * <ul>
 *   <li>{@code coins >= 1} → {@code Idle → Vending};</li>
 *   <li>{@code coins > 0}  → {@code Idle → Idle} (a guarded self-loop).</li>
 * </ul>
 *
 * For {@code coins >= 1} both guards hold, so the two transitions are enabled at
 * once — <b>nondeterminism</b>. For {@code coins <= 0} neither holds, so there is
 * no outgoing transition — <b>non-exhaustiveness</b>. The tool must report both
 * as WARN diagnostics while keeping both edges.
 */
public final class VendMachine {

    private Vend state = new Idle();   // field initializer pins the initial state

    void insert(int coins) {
        switch (state) {
            case Idle i -> {
                if (coins >= 1) this.state = new Vending();   // guard A
                if (coins > 0)  this.state = new Idle();       // guard B overlaps A
                // no handling of coins <= 0 → a coverage gap
            }
            case Vending v -> {
                // vending completes and returns to idle
                this.state = new Idle();
            }
        }
    }
}
