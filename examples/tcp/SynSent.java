package tcp;

/**
 * SYN_SENT — active OPEN sent, awaiting the peer's SYN/ACK.
 * Outgoing edges: ESTABLISHED (rcv SYN,ACK), SYN_RECEIVED (simultaneous open),
 * CLOSED (CLOSE / user timeout / RST that acks our SYN).
 */
public final class SynSent implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst() && seg.acksOurSyn())
                return Transition.to(new Closed(), Action.SIGNAL_ABORT);
            if (seg.syn() && seg.ack() && seg.acksOurSyn())
                return Transition.to(new Established(),
                        Action.SND_ACK, Action.SIGNAL_CONNECTION_OPEN);
            if (seg.syn() && !seg.ack())                                   // simultaneous open
                return Transition.to(new SynReceived(false), Action.SND_SYN_ACK);
        }
        if (event == UserCall.CLOSE || event == Timeout.USER)
            return Transition.to(new Closed(), Action.DELETE_TCB);
        return Transition.ignore(this);
    }
}
