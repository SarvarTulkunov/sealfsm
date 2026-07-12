package examples.shape;

/**
 * NEGATIVE control. A plain algebraic sum type: sealed, exhaustive, but NOT a
 * state machine — no method returns the hierarchy type, so nothing "transitions"
 * to another Shape. The classifier MUST reject this (false-positive avoidance).
 *
 * Expected: skipped with reason "looks like a plain sum type".
 */
public sealed interface Shape permits Circle, Square, Triangle {
    double area();
}
