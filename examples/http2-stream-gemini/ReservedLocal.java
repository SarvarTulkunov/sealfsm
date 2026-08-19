package http2stream;

/**
 * A stream in this state has been promised by the local endpoint
 * sending a PUSH_PROMISE frame.
 */
public record ReservedLocal() implements State {}
