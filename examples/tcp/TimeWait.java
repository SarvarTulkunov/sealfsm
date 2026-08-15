package tcp;

/**
 * TIME_WAIT — waiting 2*MSL before releasing, to absorb delayed segments
 * (RFC 9293 §3.5, §3.6.1).
 * Outgoing edges: CLOSED (2MSL timeout, or rcv RST); self-loop re-ACKs a
 * retransmitted FIN from the peer.
 */
public final class TimeWait implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst())
                return Transition.to(new Closed(), Action.SIGNAL_ABORT);
            if (seg.fin())
                return Transition.to(new TimeWait(), Action.SND_ACK);      // re-ACK peer's retransmitted FIN
        }
        if (event == Timeout.TIME_WAIT_2MSL)
            return Transition.to(new Closed(), Action.DELETE_TCB);
        return Transition.ignore(this);
    }
}
