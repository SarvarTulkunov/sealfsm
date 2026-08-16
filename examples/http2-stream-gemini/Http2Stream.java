package http2stream;

/**
 * Represents the lifecycle of an HTTP/2 stream as defined in RFC 9113, §5.1.
 * This class encapsulates the state machine and enforces valid protocol transitions.
 */
public class Http2Stream {

    private State currentState;

    public Http2Stream() {
        this.currentState = new Idle();
    }

    public State currentState() {
        return currentState;
    }

    /**
     * Processes an event and transitions the stream to its next state.
     *
     * @param event The protocol event that occurred.
     * @throws IllegalStateException if the event is not permitted in the current state.
     */
    public State handleEvent(Event event) {
        this.currentState = switch (this.currentState) {

            case Idle() -> switch (event) {
                case SEND_PUSH_PROMISE -> new ReservedLocal();
                case RECV_PUSH_PROMISE -> new ReservedRemote();
                case SEND_HEADERS, RECV_HEADERS -> new Open();
                default -> throw illegalTransition(event);
            };

            case ReservedLocal() -> switch (event) {
                case SEND_HEADERS -> new HalfClosedRemote();
                case SEND_RST_STREAM, RECV_RST_STREAM -> new Closed();
                default -> throw illegalTransition(event);
            };

            case ReservedRemote() -> switch (event) {
                case RECV_HEADERS -> new HalfClosedLocal();
                case SEND_RST_STREAM, RECV_RST_STREAM -> new Closed();
                default -> throw illegalTransition(event);
            };

            case Open() -> switch (event) {
                case RECV_END_STREAM -> new HalfClosedRemote();
                case SEND_END_STREAM -> new HalfClosedLocal();
                case SEND_RST_STREAM, RECV_RST_STREAM -> new Closed();
                default -> throw illegalTransition(event);
            };

            case HalfClosedRemote() -> switch (event) {
                case SEND_END_STREAM, SEND_RST_STREAM, RECV_RST_STREAM -> new Closed();
                default -> throw illegalTransition(event);
            };

            case HalfClosedLocal() -> switch (event) {
                case RECV_END_STREAM, SEND_RST_STREAM, RECV_RST_STREAM -> new Closed();
                default -> throw illegalTransition(event);
            };

            case Closed() ->
                throw illegalTransition(event); // Terminal state, no outbound transitions
        };

        return this.currentState;
    }

    private IllegalStateException illegalTransition(Event event) {
        String stateName = this.currentState.getClass().getSimpleName();
        return new IllegalStateException(
            "Protocol error: Invalid event %s in state %s".formatted(event, stateName)
        );
    }
}
