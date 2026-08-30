package throwcarrier;

/**
 * SNAGGED — fouled; only CLEAR is defined here.
 *
 * <p>{@code Undefined.demand(this, order)} is the NEGATIVE CONTROL for exactness:
 * that helper throws on one path and returns on another, so the edge
 * {@code Snagged --CLEAR--> Snagged} is real and must be reported. Everything
 * else in this state is undefined and contributes nothing.
 */
public final class Snagged implements Capstan {

    @Override
    public Haul on(Order order) {
        if (order == Order.CLEAR) return Undefined.demand(this, order);
        return Undefined.illegal(this, order);
    }
}
