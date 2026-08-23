package rivalseeds;

/** CENTRALIZED_DISPATCH / VALUE_RETURN, strongly connected. */
public final class DamperLogic {

    private DamperLogic() {
    }

    public static Damper next(Damper current, Drive drive) {
        return switch (current) {
            case Damper.Parked p -> switch (drive) {
                case GO -> new Damper.Cruising();
                case STOP -> new Damper.Halted();
                case IDLE -> p;
            };
            case Damper.Cruising c -> switch (drive) {
                case STOP -> new Damper.Halted();
                case IDLE -> new Damper.Parked();
                case GO -> c;
            };
            case Damper.Halted h -> switch (drive) {
                case GO -> new Damper.Cruising();
                case IDLE -> new Damper.Parked();
                case STOP -> h;
            };
        };
    }
}
