package throwcarrier;

/**
 * The rejection helpers, and the point of the fixture.
 *
 * <p>A specification with undefined cells needs somewhere to say so. Writing
 * {@code throw new IllegalStateException(...)} inline at each cell is one
 * spelling; factoring it into a helper and writing {@code return illegal(this, o)}
 * is the other, and the two must report the same transition relation — the same
 * argument F9 already settles for the centralized encoding, where
 * {@code default -> throw fail(s, e)} and {@code default -> fail(s, e)} must agree.
 */
public final class Undefined {

    private Undefined() {
    }

    /**
     * Provably cannot return: the body holds no {@code return} at all, and
     * JLS §8.4.7 forbids a non-void method whose body can complete normally. A
     * call to it therefore produces no value to wrap and no successor to reach,
     * so the arm holding it contributes NO EDGE.
     */
    public static Haul illegal(Capstan current, Order order) {
        throw new IllegalStateException(
                "undefined: " + current.getClass().getSimpleName() + " on " + order);
    }

    /**
     * NEGATIVE CONTROL for the exactness of that rule, and the sharpest one here:
     * this method throws on one path and returns on another, so it CAN return and
     * its edge must be kept. The rule is "no {@code return} anywhere", which is
     * compiler-checked; "contains a {@code throw}" is a guess, and acting on it
     * would delete a real transition with no unresolved marker — the one outcome
     * the record-everything invariant forbids.
     */
    public static Haul demand(Capstan current, Order order) {
        if (order == null) {
            throw new IllegalArgumentException("no order");
        }
        return Haul.stay(current);
    }
}
