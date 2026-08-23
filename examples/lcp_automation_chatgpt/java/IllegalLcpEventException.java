package lcpchatgpt;

/** Indicates an event that is illegal for the current RFC 1661 automaton state. */
public final class IllegalLcpEventException extends IllegalStateException {
    public IllegalLcpEventException(LcpState state, LcpEvent event) {
        super("Illegal LCP event " + event + " in state " + state.getClass().getSimpleName());
    }
}
