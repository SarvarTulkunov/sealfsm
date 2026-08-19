package websocket;

/**
 * A WebSocket connection driven through the state machine of RFC 6455.
 *
 * <p>The connection begins in {@link Connecting}. {@link #apply(WebSocketEvent)}
 * advances the current state; an event that is not valid in the current state
 * raises an {@link IllegalStateException}. {@link #transition(WebSocketState,
 * WebSocketEvent)} exposes the same logic as a pure function.
 */
public final class WebSocketConnection {

    private WebSocketState state = new Connecting();

    public WebSocketState state() {
        return state;
    }

    /** Applies an event, updates the current state, and returns the new state. */
    public WebSocketState apply(WebSocketEvent event) {
        state = transition(state, event);
        return state;
    }

    /** Computes the next state for a (state, event) pair without side effects. */
    public static WebSocketState transition(WebSocketState state, WebSocketEvent event) {
        return switch (state) {
            case Connecting connecting -> fromConnecting(connecting, event);
            case Open open -> fromOpen(open, event);
            case Closing closing -> fromClosing(closing, event);
            case Closed closed -> throw illegalTransition(closed, event);
        };
    }

    private static WebSocketState fromConnecting(Connecting state, WebSocketEvent event) {
        return switch (event) {
            case HandshakeSucceeded(String subprotocol) -> new Open(subprotocol);
            // The opening handshake never completed, so the connection is closed
            // but not cleanly (RFC 6455 §7.1.4).
            case TransportClosed() -> new Closed(CloseCodes.ABNORMAL_CLOSURE, "", false);
            default -> throw illegalTransition(state, event);
        };
    }

    private static WebSocketState fromOpen(Open state, WebSocketEvent event) {
        return switch (event) {
            case CloseFrameSent(int code, String reason) -> new Closing(code, reason);
            case CloseFrameReceived(int code, String reason) -> new Closing(code, reason);
            // Transport dropped without a closing handshake: an abnormal closure.
            case TransportClosed() -> new Closed(CloseCodes.ABNORMAL_CLOSURE, "", false);
            default -> throw illegalTransition(state, event);
        };
    }

    private static WebSocketState fromClosing(Closing state, WebSocketEvent event) {
        return switch (event) {
            // The closing handshake completed and then the transport closed:
            // a clean close carrying the negotiated close code (RFC 6455 §7.1.4).
            case TransportClosed() -> new Closed(state.statusCode(), state.reason(), true);
            default -> throw illegalTransition(state, event);
        };
    }

    private static IllegalStateException illegalTransition(WebSocketState state, WebSocketEvent event) {
        return new IllegalStateException(
            "Illegal WebSocket transition: %s in %s".formatted(
                event.getClass().getSimpleName(), state.getClass().getSimpleName()));
    }
}
