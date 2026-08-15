package tcp;

/**
 * An incoming TCP segment, reduced to the control bits plus the two semantic
 * questions the state machine asks about the ACK field. In a full stack
 * {@code acksOurSyn} / {@code acksOurFin} are the outcome of the sequence-number
 * acceptability tests in RFC 9293 §3.4 (against SND.UNA / SND.NXT).
 */
public record SegmentArrival(
        boolean syn,
        boolean ack,
        boolean fin,
        boolean rst,
        boolean acksOurSyn,
        boolean acksOurFin) implements Event {
}
