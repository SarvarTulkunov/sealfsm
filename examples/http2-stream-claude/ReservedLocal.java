package http2;

/**
 * A stream reserved by the local endpoint by sending a PUSH_PROMISE. The local
 * endpoint may later send HEADERS to begin pushing the promised response.
 */
public record ReservedLocal() implements StreamState {
}
