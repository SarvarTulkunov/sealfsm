package websocket;

/**
 * Drives one typical WebSocket lifecycle: a successful opening handshake, a
 * locally initiated closing handshake, and a clean transport close.
 */
public final class Main {

    public static void main(String[] args) {
        WebSocketConnection connection = new WebSocketConnection();
        print("initial", connection.state());

        WebSocketState opened = connection.apply(new HandshakeSucceeded("chat"));
        print("handshake succeeded", opened);
        if (!(opened instanceof Open(String subprotocol)) || !"chat".equals(subprotocol)) {
            throw new AssertionError("expected Open[subprotocol=chat] but was " + opened);
        }

        WebSocketState closing = connection.apply(new CloseFrameSent(CloseCodes.NORMAL_CLOSURE, "bye"));
        print("sent Close frame", closing);
        if (!(closing instanceof Closing)) {
            throw new AssertionError("expected Closing but was " + closing);
        }

        WebSocketState closed = connection.apply(new TransportClosed());
        print("transport closed", closed);
        if (!(closed instanceof Closed(int code, String reason, boolean clean))
                || code != CloseCodes.NORMAL_CLOSURE || !clean) {
            throw new AssertionError("expected clean Closed[1000] but was " + closed);
        }

        System.out.println("Completed a clean close with code " + CloseCodes.NORMAL_CLOSURE + ".");
    }

    private static void print(String step, WebSocketState state) {
        System.out.printf("%-22s -> %s%n", step, state);
    }
}
