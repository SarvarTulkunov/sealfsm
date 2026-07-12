package examples.door;

/** The centralized transition function: T transition(T current, Event e). */
public final class DoorMachine {

    public static Door transition(Door current, Event event) {
        return switch (current) {
            case Closed c -> (event instanceof Lock) ? new Locked() : new Open();
            case Open o   -> new Closed();
            case Locked l -> (event instanceof Unlock) ? new Closed() : current;
        };
    }
}
