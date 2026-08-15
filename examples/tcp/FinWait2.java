package tcp;

/**
 * FIN_WAIT_2 — our FIN acknowledged, awaiting the peer's FIN.
 * Outgoing edges: TIME_WAIT (rcv FIN), CLOSED (rcv RST).
 */
public final class FinWait2 implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst())
                return Transition.to(new Closed(), Action.SIGNAL_ABORT);
            if (seg.fin())
                return Transition.to(new TimeWait(),
                        Action.SND_ACK, Action.START_TIME_WAIT_TIMER);
        }
        return Transition.ignore(this);
    }
}
