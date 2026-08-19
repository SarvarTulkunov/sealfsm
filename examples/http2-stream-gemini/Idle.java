package http2stream;

/**
 * The initial state. No frames have been exchanged on this stream yet.
 */
public record Idle() implements State {}
