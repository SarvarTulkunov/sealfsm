package tcp;

/**
 * FIN_WAIT_1 — our FIN sent, awaiting its ACK and/or the peer's FIN.
 * Outgoing edges: FIN_WAIT_2 (rcv ACK of FIN), CLOSING (rcv FIN),
 * TIME_WAIT (combined FIN+ACK of our FIN — Figure 5 Note 2), CLOSED (rcv RST).
 */
public final class FinWait1 implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst())
                return Transition.to(new Closed(), Action.SIGNAL_ABORT);
            if (seg.fin() && seg.ack() && seg.acksOurFin())               // Note 2
                return Transition.to(new TimeWait(),
                        Action.SND_ACK, Action.START_TIME_WAIT_TIMER);
            if (seg.ack() && seg.acksOurFin())
                return Transition.to(new FinWait2());
            if (seg.fin())
                return Transition.to(new Closing(), Action.SND_ACK);
        }
        return Transition.ignore(this);
    }
}
