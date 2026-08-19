package http2stream;

/**
 * The events (frame transmissions or flag observations) that drive state transitions.
 */
public enum Event {
    SEND_HEADERS,
    RECV_HEADERS,
    SEND_PUSH_PROMISE,
    RECV_PUSH_PROMISE,
    SEND_END_STREAM,
    RECV_END_STREAM,
    SEND_RST_STREAM,
    RECV_RST_STREAM
}
