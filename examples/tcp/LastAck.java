package tcp;

/**
 * LAST_ACK — our FIN sent after a passive close, awaiting its ACK.
 * Outgoing edges: CLOSED (rcv ACK of FIN, or rcv RST).
 */
public final class LastAck implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst())
                return Transition.to(new Closed(), Action.SIGNAL_ABORT);
            if (seg.ack() && seg.acksOurFin())
                return Transition.to(new Closed(), Action.DELETE_TCB);
        }
        return Transition.ignore(this);
    }
}
