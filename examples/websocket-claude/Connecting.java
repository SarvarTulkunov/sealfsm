package websocket;

/**
 * The initial state. The opening handshake (RFC 6455 §4) has been started but
 * has not yet completed; no WebSocket frames may be exchanged.
 */
public record Connecting() implements WebSocketState {}
