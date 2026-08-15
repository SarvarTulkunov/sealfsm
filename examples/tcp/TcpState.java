package tcp;

/**
 * Sealed hierarchy of TCP connection states (RFC 9293 §3.3.2, Figure 5).
 *
 * <p>Every permitted subtype is exactly one FSM state and implements
 * {@link #on(Event)}, whose body enumerates that state's outgoing transitions
 * as direct references to sibling state types (via {@code new SomeState()}).
 * The automaton is therefore fully recoverable from the hierarchy by static
 * analysis:
 * <ul>
 *   <li>the state set = the {@code permits} clause below;</li>
 *   <li>the edges = the constructor calls inside each subtype's {@code on};</li>
 *   <li>the transition guards = the {@code if} conditions over {@link Event}
 *       subtypes in each {@code on} body.</li>
 * </ul>
 *
 * <p>No transition lives outside a state class — the global RST rule of
 * §3.5.3 is inlined into each state — so an analyzer reading only the sealed
 * classes sees the complete FSM.
 *
 * <p>Requires Java 17+ (sealed types, records, pattern {@code instanceof}).
 */
public sealed interface TcpState
        permits Closed, Listen, SynSent, SynReceived, Established,
                FinWait1, FinWait2, CloseWait, Closing, LastAck, TimeWait {

    /** Compute the transition for an incoming event from this state. */
    Transition on(Event event);

    /** Short state name, used for logging and FSM-extraction output. */
    default String label() {
        return getClass().getSimpleName();
    }
}
