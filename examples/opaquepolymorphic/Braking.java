package opaquepolymorphic;

/** Successor out of reach behind a SUPPLIER: produced by a callable, not an expression. */
public record Braking(int rpm) implements Rotor {

    @Override
    public Rotor advance(int delta) {
        return Registry.fromSupplier(() -> Registry.BY_KEY.get("braking/" + delta));
    }
}
