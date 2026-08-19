package websocket;

/**
 * Lifecycle state of a WebSocket connection as defined by RFC 6455:
 * CONNECTING (§4.1), OPEN (§4.1, §4.2.2), CLOSING (§7.1.3), and CLOSED (§7.1.4).
 *
 * <p>A connection starts in {@link Connecting} and ends in {@link Closed}.
 * Transitions are computed by {@link WebSocketConnection#transition}.
 */
public sealed interface WebSocketState
        permits Connecting, Open, Closing, Closed {
}
