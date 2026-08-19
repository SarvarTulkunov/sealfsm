package valueforms;

/**
 * A successor-computing helper living outside the hierarchy. Following it would
 * cross the intra-procedural scope line, so edges routed through it stay
 * unresolved — visible in the output and in recall, never invented.
 */
public final class Router {

    private Router() {
    }

    public static Signal pick(Tick tick) {
        return tick == Tick.ARM ? Armed.INSTANCE : new Firing();
    }
}
