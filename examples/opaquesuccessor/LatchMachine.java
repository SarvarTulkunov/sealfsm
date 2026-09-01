package opaquesuccessor;

import java.util.Map;

/**
 * A centralized transition function whose commit is proven by its own codomain
 * and whose successors are, by construction, unrecoverable.
 *
 * <p>{@link #next} is the dispatch: one exhaustive switch over the state, and
 * {@code return switch (...)} in a method returning {@code Latch}, which is the
 * {@code VALUE_RETURN} commit observed {@code DIRECT}ly. Nothing about the
 * commit is in doubt.
 *
 * <p>{@link #pick} is the other half. Its declared return type is the hierarchy
 * root, so the fold is entitled to summarise it — and cannot: its only
 * {@code return} sits inside a {@code synchronized} block, which the walker does
 * not descend, so the fold reaches no return at all. The successor is also
 * computed through a {@code Map} lookup keyed on a string, which nothing in the
 * resolver can follow even if the walk did reach it. Both are deliberate: the
 * first is what makes the count zero, the second is what keeps it zero if the
 * first is ever fixed.
 */
public final class LatchMachine {

    private final Map<String, Latch> table = Map.of();
    private final Object lock = new Object();

    /** The dispatch. Exhaustive over the permits clause; commit proven by codomain. */
    public Latch next(Latch state, Signal signal) {
        return switch (state) {
            case Idle i -> pick(i, signal);
            case Armed a -> pick(a, signal);
            case Fired f -> pick(f, signal);
            case Spent s -> pick(s, signal);
        };
    }

    /**
     * Returns the hierarchy root, so the codomain proves the commit — and returns
     * it from inside a {@code synchronized} block, so the walk never gets there.
     */
    private Latch pick(Latch current, Signal signal) {
        synchronized (lock) {
            return table.get(current.getClass().getSimpleName() + '/' + signal.name());
        }
    }
}
