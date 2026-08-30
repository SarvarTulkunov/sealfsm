package throwcarrier;

/**
 * TAUT — line under load. Edges: PAY_OUT to Slack, and a default self-loop.
 *
 * <p>Two of the three arms are the point of the fixture, and they sit in one
 * method so that only the callee's body can distinguish them:
 * <ul>
 *   <li>{@code Undefined.illegal(new Snagged(), order)} is THE FINDING in its
 *       second spelling — the fabricated edge lands on a state that is not the
 *       current one, so suppressing it is not a special case about self-loops;</li>
 *   <li>{@code Haul.stay(this)} is the NEGATIVE CONTROL — the same call shape,
 *       a returning body, and a real self-loop that must survive.</li>
 * </ul>
 */
public final class Taut implements Capstan {

    @Override
    public Haul on(Order order) {
        if (order == Order.PAY_OUT) return Haul.to(new Slack());
        if (order == Order.SNAG) return Undefined.illegal(new Snagged(), order);
        return Haul.stay(this);
    }
}
