package lcpchatgpt;

/** The Closed state: the lower layer is available, but the link is not administratively open. */
public record Closed() implements LcpState {
}
