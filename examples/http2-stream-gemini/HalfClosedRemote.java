package http2stream;

/**
 * The remote peer has sent an END_STREAM flag and can no longer send data frames.
 */
public record HalfClosedRemote() implements State {}
