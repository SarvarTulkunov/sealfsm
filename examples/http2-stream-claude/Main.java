package http2;

/**
 * Drives one typical client request/response exchange through a stream:
 * the client opens the stream with HEADERS, receives the server's complete
 * response, then finishes sending its own body — ending in {@link Closed}.
 */
public final class Main {

    public static void main(String[] args) {
        StreamStateMachine stream = new StreamStateMachine();
        print("initial", stream.state());

        expect(stream.state(), Idle.class);

        stream.apply(new Send(Signal.HEADERS));           // client sends request headers
        print("send HEADERS", stream.state());
        expect(stream.state(), Open.class);

        stream.apply(new Recv(Signal.END_STREAM));        // server response completes
        print("recv END_STREAM", stream.state());
        expect(stream.state(), HalfClosedRemote.class);

        stream.apply(new Send(Signal.END_STREAM));        // client finishes its body
        print("send END_STREAM", stream.state());
        expect(stream.state(), Closed.class);

        System.out.println("Reached terminal state as expected.");
    }

    private static void print(String step, StreamState state) {
        System.out.printf("%-18s -> %s%n", step, state.getClass().getSimpleName());
    }

    private static void expect(StreamState actual, Class<? extends StreamState> expected) {
        if (!expected.isInstance(actual)) {
            throw new AssertionError(
                    "expected " + expected.getSimpleName() + " but was " + actual);
        }
    }
}
