package http2;

/**
 * The lifecycle state of a single HTTP/2 stream, as defined by the stream state
 * diagram in RFC 9113, Section 5.1.
 *
 * <p>A stream begins in {@link Idle} and ends in {@link Closed}. Each permitted
 * record is one state; transitions between them are computed by
 * {@link StreamStateMachine#next(StreamState, StreamEvent)}.
 */
public sealed interface StreamState
        permits Idle, ReservedLocal, ReservedRemote, Open,
                HalfClosedLocal, HalfClosedRemote, Closed {
}
