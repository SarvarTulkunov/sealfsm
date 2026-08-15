package tcp;

/**
 * CLOSE_WAIT — peer's FIN acknowledged, awaiting the local CLOSE.
 * Outgoing edges: LAST_ACK (CLOSE), CLOSED (rcv RST).
 */
public final class CloseWait implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg && seg.rst())
            return Transition.to(new Closed(), Action.SIGNAL_ABORT);
        if (event == UserCall.CLOSE)
            return Transition.to(new LastAck(), Action.SND_FIN);
        return Transition.ignore(this);
    }
}
