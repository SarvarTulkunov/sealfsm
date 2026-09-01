package emptycandidate;

/**
 * An exhaustive fold, not a transition function: the discrimination is real and
 * complete, and every arm produces a {@code String}.
 *
 * <p>This is what makes the hierarchy a Tier 3 candidate rather than a plain
 * rejection — {@code examples/shape} has no dispatch at all and is correctly not
 * a candidate. Here the state IS discriminated, so the tool can name the sites it
 * looked at, and it still refuses to claim a transition relation.
 */
public final class ChannelReport {

    private String label = "";

    public String describe(Channel channel) {
        return switch (channel) {
            case Idle i -> "idle";
            case Reading r -> "reading";
            case Writing w -> "writing";
            case Draining d -> d.name().toLowerCase();
        };
    }

    /** The same fold in the other accepted commit position, folding into a field. */
    public void record(Channel channel) {
        this.label = switch (channel) {
            case Idle i -> "idle";
            case Active a -> "active";
            case Draining d -> "draining";
        };
    }
}
