package carrierreject;

/**
 * The rejection helper, and the whole point of the fixture.
 *
 * <p>{@code illegal(current, event)} is syntactically INDISTINGUISHABLE from a
 * carrier factory: a call whose own type is outside the hierarchy, carrying a
 * hierarchy-typed argument. Nothing in the expression separates the two — only
 * the callee's body does, and JLS 8.4.7 makes "no return anywhere in a non-void
 * method" a proof that it cannot return normally, not a guess.
 *
 * <p>Its argument is deliberately the CURRENT state, which is how such a helper
 * is almost always called. That makes the fabrication a self-loop: one per
 * specification-undefined cell, and a real transition table has many.
 */
public final class Undefined {

    public static Move illegal(Cell current, int event) {
        throw new IllegalStateException("no transition defined for " + current + " on " + event);
    }

    /**
     * The NEGATIVE CONTROL, and the reason the rule must be JLS 8.4.7's "no
     * return anywhere" rather than "contains a throw". This one throws on one
     * path and returns on another, so it CAN produce a successor and its edge
     * must be kept. A "contains a throw" rule would delete a real transition with
     * no unresolved marker.
     */
    public static Move recover(Cell current, int event) {
        if (event < 0) {
            throw new IllegalArgumentException("negative event");
        }
        return new Move(new Ready(), "recover");
    }
}
