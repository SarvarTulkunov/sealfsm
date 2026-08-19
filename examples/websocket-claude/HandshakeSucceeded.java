package websocket;

/**
 * The opening handshake was validated and the connection is established
 * (RFC 6455 §4.1).
 *
 * @param subprotocol the negotiated subprotocol, or {@code null} if none
 */
public record HandshakeSucceeded(String subprotocol) implements WebSocketEvent {}
