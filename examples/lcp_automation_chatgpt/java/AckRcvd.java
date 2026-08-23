package lcpchatgpt;

/** The Ack-Received state: our Configure-Request has been acknowledged, but our Configure-Ack has not been sent. */
public record AckRcvd() implements LcpState {
}
