package websocket;

/**
 * The local endpoint sent a Close control frame, starting the closing
 * handshake (RFC 6455 §5.5.1, §7.1.2).
 *
 * @param statusCode the status code placed in the Close frame (RFC 6455 §7.4)
 * @param reason     the optional close reason, possibly empty
 */
public record CloseFrameSent(int statusCode, String reason) implements WebSocketEvent {}
