package bindingframes;

/**
 * A class with two implementations of each method in the model: its own, and
 * {@link LoudRouter}'s. A call through a {@code Router}-typed receiver may run
 * either, so the analysis may not summarise one body as if it were the call.
 */
public class Router {

    /** Forwards its argument. {@link LoudRouter} overrides it to return Idle instead. */
    public Pump pick(Pump p) {
        return p;
    }

    /** Always throws here — and returns a state in {@link LoudRouter}. */
    public Pump reject(Pump p) {
        throw new IllegalStateException("no route from " + p);
    }
}
