import java.util.List;

/** The result of applying one LCP event: the next state and RFC-defined actions. */
public record LcpTransition(LcpState state, List<LcpAction> actions) {
    public LcpTransition {
        actions = List.copyOf(actions);
    }
}
