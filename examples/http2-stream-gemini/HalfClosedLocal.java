package http2stream;

/**
 * The local endpoint has sent an END_STREAM flag and can no longer send data frames.
 */
public record HalfClosedLocal() implements State {}
