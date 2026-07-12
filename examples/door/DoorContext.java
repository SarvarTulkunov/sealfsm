package examples.door;

/** Holds current state; lets the tool detect the initial state (Closed). */
public final class DoorContext {
    private Door current = new Closed();

    public void on(Event event) {
        current = DoorMachine.transition(current, event);
    }

    public Door current() {
        return current;
    }
}
