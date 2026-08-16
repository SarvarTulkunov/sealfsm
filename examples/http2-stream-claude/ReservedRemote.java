package http2;

/**
 * A stream reserved by the remote endpoint, learned by receiving a PUSH_PROMISE.
 * The local endpoint awaits the pushed response HEADERS from its peer.
 */
public record ReservedRemote() implements StreamState {
}
