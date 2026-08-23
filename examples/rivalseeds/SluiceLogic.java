package rivalseeds;

/**
 * CENTRALIZED_DISPATCH / VALUE_RETURN. Every state is reachable from both of the
 * others, so no state has an empty set of incoming edges and the structural
 * initial-state rule abstains — leaving the seeded-field rule as the only one
 * with anything to say, which is what makes the disagreement observable.
 */
public final class SluiceLogic {

    private SluiceLogic() {
    }

    public static Sluice next(Sluice current, Cmd cmd) {
        return switch (current) {
            case Sluice.Shut s -> switch (cmd) {
                case OPEN -> new Sluice.Flowing();
                case JAM -> new Sluice.Blocked();
                case CLOSE -> s;
            };
            case Sluice.Flowing f -> switch (cmd) {
                case CLOSE -> new Sluice.Shut();
                case JAM -> new Sluice.Blocked();
                case OPEN -> f;
            };
            case Sluice.Blocked b -> switch (cmd) {
                case CLOSE -> new Sluice.Shut();
                case OPEN -> new Sluice.Flowing();
                case JAM -> b;
            };
        };
    }
}
