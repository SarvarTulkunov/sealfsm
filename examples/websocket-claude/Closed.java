package websocket;

/**
 * The terminal state (RFC 6455 §7.1.4): the underlying transport is closed and
 * no further frames may be exchanged.
 *
 * @param statusCode the connection close code (RFC 6455 §7.1.5)
 * @param reason     the connection close reason, possibly empty (§7.1.6)
 * @param clean      {@code true} if the transport closed after the closing
 *                   handshake completed; {@code false} for an abnormal closure
 */
public record Closed(int statusCode, String reason, boolean clean) implements WebSocketState {}
