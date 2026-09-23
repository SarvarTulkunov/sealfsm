package bindingframes;

/** Static factories: the successor is PRODUCED by a call, not written as a construction. */
public final class Valves {
    private Valves() {}

    public static Valve shut() { return new Valve.Shut(); }
    public static Valve opening() { return new Valve.Opening(); }
    public static Valve open() { return new Valve.Open(); }
}
