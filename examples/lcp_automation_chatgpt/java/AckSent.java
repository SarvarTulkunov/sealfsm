package lcpchatgpt;

/** The Ack-Sent state: our Configure-Request and Configure-Ack have been sent, awaiting the peer's Configure-Ack. */
public record AckSent() implements LcpState {
}
