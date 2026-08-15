package tcp;

/**
 * ESTABLISHED — open connection, the normal data-transfer state.
 * Outgoing edges: FIN_WAIT_1 (CLOSE), CLOSE_WAIT (rcv FIN), CLOSED (rcv RST).
 */
public final class Established implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst())
                return Transition.to(new Closed(), Action.SIGNAL_ABORT);
            if (seg.fin())
                return Transition.to(new CloseWait(),
                        Action.SND_ACK, Action.SIGNAL_CONNECTION_CLOSING);
            // TODO: in-window data transfer — accept data, advance RCV.NXT,
            //       ACK, deliver to the application (RFC 9293 §3.4, §3.10).
        }
        if (event == UserCall.CLOSE)
            return Transition.to(new FinWait1(), Action.SND_FIN);
        return Transition.ignore(this);
    }
}
