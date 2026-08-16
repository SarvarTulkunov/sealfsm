package http2;

/**
 * A fully active stream. Both endpoints may send frames freely. When either side
 * sends END_STREAM the stream becomes half-closed in that direction.
 */
public record Open() implements StreamState {
}
