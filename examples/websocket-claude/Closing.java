package websocket;

/**
 * The closing handshake has started (RFC 6455 §7.1.3): a Close control frame
 * has been sent or received. No further data frames are sent; the connection
 * is waiting for the underlying transport to close.
 *
 * @param statusCode the close status code from the Close frame (RFC 6455 §7.4)
 * @param reason     the accompanying close reason, possibly empty (§7.1.6)
 */
public record Closing(int statusCode, String reason) implements WebSocketState {}
