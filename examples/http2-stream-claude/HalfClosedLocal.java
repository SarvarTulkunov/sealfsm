package http2;

/**
 * The local endpoint has finished sending (it sent END_STREAM) but may still
 * receive frames. Receiving END_STREAM from the peer closes the stream.
 */
public record HalfClosedLocal() implements StreamState {
}
