package bindingframes;

/** A static factory for a receiver, and a table whose lookup has no body in the source set. */
public final class Gears {
    private Gears() {}

    static GearTable TABLE;

    public static Gear reverse() { return new Gear.Reverse(); }
}
