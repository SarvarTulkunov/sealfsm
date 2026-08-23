package rivalseeds;

/** CENTRALIZED_DISPATCH / VALUE_RETURN, strongly connected. */
public final class RatchetLogic {

    private RatchetLogic() {
    }

    public static Ratchet next(Ratchet current, Pawl pawl) {
        return switch (current) {
            case Ratchet.Locked l -> switch (pawl) {
                case RELEASE -> new Ratchet.Free();
                case ENGAGE -> l;
            };
            case Ratchet.Free f -> switch (pawl) {
                // The flyweight, reached through its root-typed constant.
                case ENGAGE -> Ratchet.Locked.INSTANCE;
                case RELEASE -> f;
            };
        };
    }
}
