package http2;

/**
 * The remote endpoint has finished sending (the local endpoint received
 * END_STREAM) but the local endpoint may still send frames. Sending END_STREAM
 * closes the stream.
 */
public record HalfClosedRemote() implements StreamState {
}
