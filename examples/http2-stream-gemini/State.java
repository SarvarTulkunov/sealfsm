package http2stream;

/**
 * Represents the possible states of an HTTP/2 stream (RFC 9113, §5.1).
 * Modeled as a sealed interface with record implementations for Java 21 exhaustiveness.
 */
public sealed interface State
        permits Idle, ReservedLocal, ReservedRemote, Open,
                HalfClosedRemote, HalfClosedLocal, Closed {
}
