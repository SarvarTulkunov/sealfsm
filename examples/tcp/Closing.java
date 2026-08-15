package tcp;

/**
 * CLOSING — simultaneous close: both FINs exchanged, awaiting ACK of our FIN.
 * Outgoing edges: TIME_WAIT (rcv ACK of FIN), CLOSED (rcv RST).
 */
public final class Closing implements TcpState {

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst())
                return Transition.to(new Closed(), Action.SIGNAL_ABORT);
            if (seg.ack() && seg.acksOurFin())
                return Transition.to(new TimeWait(), Action.START_TIME_WAIT_TIMER);
        }
        return Transition.ignore(this);
    }
}
