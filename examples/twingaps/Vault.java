package twingaps;

/**
 * GAP IDENTITY FIXTURE. A gap is identified by the program point that computes
 * its unknown successor, not only by the state it leaves. {@code fromLocked}
 * returns twice from inside a {@code synchronized} block, which the walker does
 * not descend (the standing probe of examples/hiddenreturns), so each return is
 * recorded unresolved: two unknown successors out of {@code Locked}, under the
 * same (absent) event and guard. Compared on (from, to, event, guard) alone they
 * were one transition, and the set kept one — a recorded gap dropped with no
 * marker. Expected: 3 states, 4 transitions, 2/4 — where it reported 2/3.
 *
 * <p>The other direction, that two program points yielding the same RESOLVED
 * edge are one transition, is a property of the relation tuple and is pinned
 * directly on {@code Transition} (TransitionIdentityTest).
 */
public sealed interface Vault permits Locked, Open, Alarmed {
}

record Locked() implements Vault {}

record Open() implements Vault {}

record Alarmed() implements Vault {}

enum Cmd { KEY, KICK }

final class VaultMachine {
    private static final Object LOCK = new Object();

    static Vault next(Vault current, Cmd cmd) {
        return switch (current) {
            case Locked l -> fromLocked(cmd);
            case Open o -> new Locked();
            case Alarmed a -> a;
        };
    }

    private static Vault fromLocked(Cmd cmd) {
        synchronized (LOCK) {
            if (cmd == Cmd.KEY) {
                return new Open();
            }
            return new Alarmed();
        }
    }
}

/** The store-back. Unseeded, so no initial-state rule is handed a seed. */
final class VaultDriver {
    private Vault state;

    void press(Cmd cmd) {
        state = VaultMachine.next(state, cmd);
    }
}
