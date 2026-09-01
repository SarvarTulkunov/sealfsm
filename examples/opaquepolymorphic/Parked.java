package opaquepolymorphic;

/** Successor out of reach by MAP LOOKUP on a computed key. */
public record Parked(int rpm) implements Rotor {

    @Override
    public Rotor advance(int delta) {
        return Registry.BY_KEY.get("parked/" + (rpm + delta));
    }
}
