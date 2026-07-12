package examples.traffic;

/** Holds the current state; used by the tool to detect the initial state. */
public final class TrafficController {
    private TrafficLight current = new Red();

    public void tick() {
        current = current.next();
    }

    public TrafficLight current() {
        return current;
    }
}
