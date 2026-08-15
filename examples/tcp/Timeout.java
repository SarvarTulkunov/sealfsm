package tcp;

/** Timer expiries (RFC 9293 §3.10). */
public enum Timeout implements Event {
    TIME_WAIT_2MSL, RETRANSMISSION, USER
}
