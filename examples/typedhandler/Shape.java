package typedhandler;

/**
 * F35 NEGATIVE CONTROL: a sum type with ONE typed converter. Its signature is the
 * signature of {@code OrderState handle(Placed)}, and a value-returning host is
 * committed by its codomain alone, so only the size of the family separates
 * them. One source state is a conversion, not a dispatch. Must be REJECTED, and
 * not a candidate: nothing discriminates it. Was a machine: 0/1 before F34, a
 * clean-looking 1/1 `Circle -> Square` after.
 */
public sealed interface Shape permits Shape.Circle, Shape.Square {
    record Circle(double r) implements Shape {}
    record Square(double side) implements Shape {}
}

final class Geometry {
    static Shape boundingBox(Shape.Circle c) { return new Shape.Square(2 * c.r()); }
}
