package tcp;

import java.util.List;

/**
 * Thin driver that holds the current {@link TcpState} and applies events.
 *
 * <p>All FSM logic lives in the sealed state hierarchy; this class only swaps
 * in the next state and surfaces the actions. It is the single mutable point,
 * which keeps the state classes pure and side-effect free — convenient both for
 * unit testing and for static analysis (nothing here changes the FSM's shape).
 */
public final class TcpConnection {

    private TcpState state = new Closed();

    public TcpState state() {
        return state;
    }

    /** Apply one event, advance the state, and return the actions to perform. */
    public List<Action> apply(Event event) {
        Transition t = state.on(event);
        this.state = t.next();
        return t.actions();
    }
}
