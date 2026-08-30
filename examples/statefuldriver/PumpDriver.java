package statefuldriver;

/**
 * {@code return switch (state)} on a driver that owns the state, delegating one
 * helper per state. Recognised as a dispatch all along; its helpers were the
 * casualty.
 */
public final class PumpDriver {

    private Pump state = new Idle();

    public Pump step(Command command) {
        return switch (state) {
            case Idle i -> fromIdle(i, command);
            case Priming p -> fromPriming(p, command);
            case Running r -> fromRunning(r, command);
        };
    }

    private static Pump fromIdle(Idle current, Command command) {
        return switch (command) {
            case START -> new Priming();
            case PRIME -> new Priming();
            case STOP -> current;
        };
    }

    private static Pump fromPriming(Priming current, Command command) {
        return switch (command) {
            case START -> new Running();
            case PRIME -> current;
            case STOP -> new Idle();
        };
    }

    private static Pump fromRunning(Running current, Command command) {
        return switch (command) {
            case START -> current;
            case PRIME -> new Priming();
            case STOP -> new Idle();
        };
    }
}
