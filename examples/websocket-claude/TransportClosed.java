package websocket;

/**
 * The underlying TCP (or TLS) connection was closed (RFC 6455 §7.1.4). This may
 * follow a completed closing handshake (a clean close) or happen abnormally.
 */
public record TransportClosed() implements WebSocketEvent {}
