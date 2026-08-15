package tcp;

/**
 * CLOSED — no connection exists (the "fictional" state; no TCB).
 * Outgoing edges: LISTEN (passive OPEN), SYN_SENT (active OPEN).
 */
public final class Closed implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event == UserCall.PASSIVE_OPEN)
            return Transition.to(new Listen(), Action.CREATE_TCB);
        if (event == UserCall.ACTIVE_OPEN)
            return Transition.to(new SynSent(), Action.CREATE_TCB, Action.SND_SYN);
        return Transition.ignore(this);
    }
}
