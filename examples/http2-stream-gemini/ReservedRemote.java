package http2stream;

/**
 * A stream in this state has been reserved by the remote peer
 * sending a PUSH_PROMISE frame.
 */
public record ReservedRemote() implements State {}
