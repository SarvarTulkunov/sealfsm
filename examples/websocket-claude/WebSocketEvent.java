package websocket;

/**
 * An input that can drive a WebSocket connection from one state to another.
 */
public sealed interface WebSocketEvent
        permits HandshakeSucceeded, CloseFrameSent, CloseFrameReceived, TransportClosed {
}
