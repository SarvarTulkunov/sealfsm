package examples.foreignfold;

/**
 * Two exhaustive folds over the hierarchy, in the two commit positions the
 * widened recognizer accepts — a {@code return} and a field assignment — but
 * with a foreign codomain in both. Neither is a transition producer.
 */
public final class ModeReporter {

    private Mode mode = new Stopped();
    private String label = "";
    private int rank;

    /** Fold in RETURN position: the method's codomain is String, not Mode. */
    public String describe() {
        return switch (mode) {
            case Fast() -> "fast";
            case Slow() -> "slow";
            case Stopped() -> "stopped";
        };
    }

    /** Fold in FIELD-ASSIGNMENT position: the target field is String, not Mode. */
    public void refresh() {
        label = switch (mode) {
            case Fast() -> "F";
            case Slow() -> "S";
            case Stopped() -> "-";
        };
        rank = switch (mode) {
            case Fast() -> 2;
            case Slow() -> 1;
            case Stopped() -> 0;
        };
    }

    public String label() {
        return label;
    }

    public int rank() {
        return rank;
    }
}
