package tcp;

/** Runnable demo: one full client lifecycle (active open, then active close). */
public final class Main {

    public static void main(String[] args) {
        TcpConnection c = new TcpConnection();
        log(c, "start");

        c.apply(UserCall.ACTIVE_OPEN);                                       // -> SynSent
        log(c, "active OPEN");

        c.apply(new SegmentArrival(true, true, false, false, true, false));  // rcv SYN,ACK -> Established
        log(c, "rcv SYN,ACK");

        c.apply(UserCall.CLOSE);                                             // -> FinWait1
        log(c, "CLOSE");

        c.apply(new SegmentArrival(false, true, false, false, false, true)); // rcv ACK of FIN -> FinWait2
        log(c, "rcv ACK of FIN");

        c.apply(new SegmentArrival(false, false, true, false, false, false));// rcv FIN -> TimeWait
        log(c, "rcv FIN");

        c.apply(Timeout.TIME_WAIT_2MSL);                                    // -> Closed
        log(c, "2MSL timeout");
    }

    private static void log(TcpConnection c, String event) {
        System.out.printf("%-16s state = %s%n", event, c.state().label());
    }
}
