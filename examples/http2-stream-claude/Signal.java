package http2;

/**
 * The frame-level signals that drive HTTP/2 stream state transitions.
 * Only the aspects of a frame that the stream lifecycle depends on are modeled.
 */
public enum Signal {

    /** A HEADERS frame (including any implied CONTINUATION frames). */
    HEADERS,

    /** A PUSH_PROMISE frame reserving a stream for a server push. */
    PUSH_PROMISE,

    /** The END_STREAM flag, carried on a HEADERS or DATA frame. */
    END_STREAM,

    /** A RST_STREAM frame, abruptly terminating the stream. */
    RST_STREAM
}
