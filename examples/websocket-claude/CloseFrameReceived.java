package websocket;

/**
 * A Close control frame was received from the peer, starting the closing
 * handshake (RFC 6455 §7.1.3).
 *
 * @param statusCode the status code carried by the peer's Close frame
 * @param reason     the optional close reason, possibly empty
 */
public record CloseFrameReceived(int statusCode, String reason) implements WebSocketEvent {}
