package sidecaller;

/**
 * F39 FIXTURE, the side-table half. A plain POLYMORPHIC machine, installed by
 * {@link BarrierDriver}, and beside it {@link BarrierTable}: a method that calls
 * every per-state method by a name it switches on, and whose result is only ever
 * read as data. The table is the shape of a table-driven test, a REPL's help
 * screen, a replay tool. It must not change the machine's relation.
 */
public sealed interface Barrier permits Down, Up, Jammed {

    Barrier onCoin();

    Barrier onPass();

    Barrier onForce();
}

final class Down implements Barrier {
    @Override public Barrier onCoin() { return new Up(); }
    @Override public Barrier onPass() { return this; }
    @Override public Barrier onForce() { return new Jammed(); }
}

final class Up implements Barrier {
    @Override public Barrier onCoin() { return this; }
    @Override public Barrier onPass() { return new Down(); }
    @Override public Barrier onForce() { return new Jammed(); }
}

final class Jammed implements Barrier {
    @Override public Barrier onCoin() { throw new IllegalStateException("jammed"); }
    @Override public Barrier onPass() { throw new IllegalStateException("jammed"); }
    @Override public Barrier onForce() { return new Down(); }
}

/** The store-back. Unseeded, so no initial-state rule is handed a seed. */
final class BarrierDriver {
    private Barrier state;

    void coin() { state = state.onCoin(); }

    void pass() { state = state.onPass(); }

    void force() { state = state.onForce(); }

    void reset() { state = BarrierService.reset(state); }
}

/**
 * A centralized transition function the driver installs. The side table below
 * calls it too, and that call has ONE target: before F39 it made {@code reset} a
 * helper of a method nothing walks, and its three edges went with it.
 */
final class BarrierService {
    static Barrier reset(Barrier s) {
        return switch (s) {
            case Down d -> d;
            case Up u -> new Down();
            case Jammed j -> new Down();
        };
    }
}

/**
 * Takes the state and returns the state, so it is a centralized site; calls every
 * per-state method and {@code reset}, so before F39 it made all ten of them F3
 * "helpers". Its result
 * is read as data, so F36 never walks it — and nothing walked them at all.
 */
final class BarrierTable {
    static Barrier apply(Barrier s, String op) {
        switch (op) {
            case "coin":
                return s.onCoin();
            case "pass":
                return s.onPass();
            case "force":
                return s.onForce();
            case "reset":
                return BarrierService.reset(s);
            default:
                throw new IllegalArgumentException(op);
        }
    }

    static String outcome(Barrier s, String op) {
        return apply(s, op).getClass().getSimpleName();
    }
}
