package http2;

/**
 * The initial state of every stream. No frames have yet been exchanged on it.
 * Sending or receiving HEADERS opens the stream; a PUSH_PROMISE referring to it
 * reserves it.
 */
public record Idle() implements StreamState {
}
