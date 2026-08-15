package tcp;

/**
 * LISTEN — waiting for an incoming SYN.
 * Outgoing edges: SYN_RECEIVED (rcv SYN), SYN_SENT (SEND), CLOSED (CLOSE).
 * A received RST is ignored here (§3.5.3).
 */
public final class Listen implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst()) return Transition.ignore(this);                 // RST ignored in LISTEN
            if (seg.syn())
                return Transition.to(new SynReceived(true), Action.SND_SYN_ACK);
        }
        if (event == UserCall.SEND)
            return Transition.to(new SynSent(), Action.SND_SYN);
        if (event == UserCall.CLOSE)
            return Transition.to(new Closed(), Action.DELETE_TCB);
        return Transition.ignore(this);
    }
}
