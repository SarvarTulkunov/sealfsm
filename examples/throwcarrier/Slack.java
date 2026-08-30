package throwcarrier;

/**
 * SLACK — line paid out. Edges: CRANK to Taut, SNAG to Snagged.
 *
 * <p>The trailing {@code illegal(this, order)} is THE FINDING in its commonest
 * spelling: an undefined cell written as a call carrying the current state.
 * Unwrapped, its argument reads as a target and the arm reports a RESOLVED
 * self-loop the source does not contain. It must contribute no edge.
 */
public final class Slack implements Capstan {

    @Override
    public Haul on(Order order) {
        if (order == Order.CRANK) return Haul.to(new Taut());
        if (order == Order.SNAG) return Haul.to(new Snagged());
        return Undefined.illegal(this, order);
    }
}
