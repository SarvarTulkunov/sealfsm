package opaquepolymorphic;

/** Successor out of reach by ARITHMETIC INDEX into a list. */
public record Spinning(int rpm) implements Rotor {

    @Override
    public Rotor advance(int delta) {
        return Registry.RING.get(Math.floorMod(rpm + delta, Math.max(1, Registry.RING.size())));
    }
}
