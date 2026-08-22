/**
 * Represents a state in the Point-to-Point Protocol (PPP) Link Control Protocol (LCP)
 * state machine, as defined in RFC 1661.
 */
public sealed interface LcpState {
    /** The state of the link when it is physically disconnected or completely down. */
    record Initial() implements LcpState {}

    /** An administrative Open command has been initiated, but the lower layer is still down. */
    record Starting() implements LcpState {}

    /** The link is available at the lower layer (Up), but no administrative Open has been issued. */
    record Closed() implements LcpState {}

    /** The connection has been terminated, but the lower layer is still up. Waiting for Open or Down. */
    record Stopped() implements LcpState {}

    /** Actively terminating the connection, waiting for a Terminate-Ack before moving to Closed. */
    record Closing() implements LcpState {}

    /** Actively terminating an open connection, moving to Stopped state instead of Closed. */
    record Stopping() implements LcpState {}

    /** A Configure-Request has been sent, and the local peer is waiting for an acknowledgment. */
    record ReqSent() implements LcpState {}

    /** Configure-Request acknowledged by the remote peer, but we have not acknowledged their request. */
    record AckRcvd() implements LcpState {}

    /** Acknowledged the remote peer's Configure-Request, but have not yet received an acknowledgment for ours. */
    record AckSent() implements LcpState {}

    /** Both peers have acknowledged each other's configuration requests. Network protocols can now flow. */
    record Opened() implements LcpState {}
}