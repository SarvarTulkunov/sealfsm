package typedhandler;

/**
 * F35 STANDING PROBE, pinned as WRONG: two converters from two different states
 * clear the threshold, so this sum type is published as a machine
 * ({@code Meters <-> Feet}). Nothing in the signatures or the bodies separates a
 * conversion family from a handler family; only whether the result REPLACES its
 * input somewhere would, and the tool asks that of no value-returning host (a
 * converter written as {@code return switch (s)} is accepted the same way). If
 * that ever becomes the rule, this test must fail and be flipped.
 */
public sealed interface Length permits Length.Meters, Length.Feet {
    record Meters(double v) implements Length {}
    record Feet(double v) implements Length {}
}

final class Units {
    static Length toFeet(Length.Meters m) { return new Length.Feet(m.v() * 3.28084); }
    static Length toMeters(Length.Feet f) { return new Length.Meters(f.v() / 3.28084); }
}
