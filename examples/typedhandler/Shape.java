package typedhandler;

/**
 * NEGATIVE CONTROL: a sum type with ONE typed converter and no caller. Its
 * signature is the signature of {@code OrderState handle(Placed)}, and nothing in
 * the source shows its result becoming a current state, so no machine is claimed
 * (F36, thesis Decision 4).
 *
 * <p>It is not a candidate either. A single handler fixes a single source state,
 * and one discriminated state is not a dispatch. That is F35's threshold, which
 * F36 retired as an acceptance rule (a DRIVEN one-handler machine is recovered,
 * {@link Lamp}) and kept as the candidate channel's plausibility test. Was a
 * machine: 0/1 before F34, a clean-looking 1/1 {@code Circle -> Square} after.
 */
public sealed interface Shape permits Shape.Circle, Shape.Square {
    record Circle(double r) implements Shape {}
    record Square(double side) implements Shape {}
}

final class Geometry {
    static Shape boundingBox(Shape.Circle c) { return new Shape.Square(2 * c.r()); }
}
