package lcpchatgpt;

/** The Request-Sent state: a Configure-Request is outstanding and no Configure-Ack is pending completion. */
public record ReqSent() implements LcpState {
}
