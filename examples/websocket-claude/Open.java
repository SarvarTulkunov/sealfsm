package websocket;

/**
 * The opening handshake completed successfully (RFC 6455 §4.1) and the
 * connection is established; either peer may send data frames.
 *
 * @param subprotocol the negotiated subprotocol (the "Subprotocol In Use" from
 *                    the server's handshake), or {@code null} if none was agreed
 */
public record Open(String subprotocol) implements WebSocketState {}
