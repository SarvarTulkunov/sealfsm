package opaquepolymorphic;

import java.util.List;
import java.util.Map;

/**
 * The three independent ways this fixture's successors are put out of reach.
 * Kept on one class so a reader can see that they are three different mechanisms
 * and not one mechanism written three times.
 */
public final class Registry {

    private Registry() {
    }

    /** A map lookup keyed on a computed string: nothing in the resolver follows it. */
    static final Map<String, Rotor> BY_KEY = Map.of();

    /** A list indexed arithmetically: the index is not a constant the resolver holds. */
    static final List<Rotor> RING = List.of();

    /** A supplier field: the value is produced by a callable, not by an expression. */
    static Rotor fromSupplier(java.util.function.Supplier<Rotor> supplier) {
        return supplier.get();
    }
}
