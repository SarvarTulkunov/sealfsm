package http2stream;

/**
 * Runnable entry point exercising the {@link Http2Stream} state machine.
 */
public final class Main {

    public static void main(String[] args) {
        testStandardRequestResponse();
        testServerPush();
        System.out.println("All state transitions completed successfully!");
    }

    /**
     * Drives a standard HTTP/2 request-response flow:
     * Idle -> (Send Headers) -> Open -> (Send End Stream) -> HalfClosedLocal -> (Recv End Stream) -> Closed
     */
    private static void testStandardRequestResponse() {
        Http2Stream stream = new Http2Stream();
        assert stream.currentState() instanceof Idle;
        System.out.println("Started in: " + stream.currentState().getClass().getSimpleName());

        stream.handleEvent(Event.SEND_HEADERS);
        assert stream.currentState() instanceof Open;
        System.out.println("Client sends HEADERS -> " + stream.currentState().getClass().getSimpleName());

        stream.handleEvent(Event.SEND_END_STREAM);
        assert stream.currentState() instanceof HalfClosedLocal;
        System.out.println("Client sends END_STREAM -> " + stream.currentState().getClass().getSimpleName());

        stream.handleEvent(Event.RECV_END_STREAM);
        assert stream.currentState() instanceof Closed;
        System.out.println("Server replies with END_STREAM -> " + stream.currentState().getClass().getSimpleName());
        System.out.println("---");
    }

    /**
     * Drives an HTTP/2 Server Push stream aborted by the client:
     * Idle -> (Recv Push Promise) -> ReservedRemote -> (Send RST) -> Closed
     */
    private static void testServerPush() {
        Http2Stream stream = new Http2Stream();

        stream.handleEvent(Event.RECV_PUSH_PROMISE);
        assert stream.currentState() instanceof ReservedRemote;
        System.out.println("Client receives PUSH_PROMISE -> " + stream.currentState().getClass().getSimpleName());

        stream.handleEvent(Event.SEND_RST_STREAM);
        assert stream.currentState() instanceof Closed;
        System.out.println("Client cancels via RST_STREAM -> " + stream.currentState().getClass().getSimpleName());
    }
}
