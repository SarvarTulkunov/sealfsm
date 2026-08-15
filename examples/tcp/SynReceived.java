package tcp;

/**
 * SYN_RECEIVED — SYN,ACK sent, awaiting the final ACK of our SYN.
 *
 * <p>This is the one data-carrying state: {@code viaPassiveOpen} records how it
 * was entered, because a received RST returns to LISTEN only if we arrived here
 * from a passive OPEN (RFC 9293 Figure 5, Note 1; requirement MUST-11), and
 * aborts to CLOSED otherwise. For FSM extraction this is a useful context-
 * sensitive case: the single {@code on} body yields two distinct RST edges
 * (LISTEN and CLOSED) selected by the field set at construction.
 *
 * <p>Outgoing edges: ESTABLISHED (rcv ACK of SYN), FIN_WAIT_1 (CLOSE),
 * LISTEN or CLOSED (rcv RST).
 */
public final class SynReceived implements TcpState {

    private final boolean viaPassiveOpen;

    public SynReceived(boolean viaPassiveOpen) {
        this.viaPassiveOpen = viaPassiveOpen;
    }

    public boolean viaPassiveOpen() {
        return viaPassiveOpen;
    }

    @Override
    public Transition on(Event event) {
        if (event instanceof SegmentArrival seg) {
            if (seg.rst())
                return viaPassiveOpen
                        ? Transition.to(new Listen())                     // Note 1
                        : Transition.to(new Closed(), Action.SIGNAL_ABORT);
            if (seg.ack() && seg.acksOurSyn())
                return Transition.to(new Established(), Action.SIGNAL_CONNECTION_OPEN);
        }
        if (event == UserCall.CLOSE)
            return Transition.to(new FinWait1(), Action.SND_FIN);
        return Transition.ignore(this);
    }
}
