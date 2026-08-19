package http2;

/**
 * The terminal state. The stream has been fully closed, either by END_STREAM in
 * both directions or by a RST_STREAM. No further transitions are defined.
 */
public record Closed() implements StreamState {
}
