package http2;

/**
 * Drives a single HTTP/2 stream through the state machine of RFC 9113,
 * Section 5.1.
 *
 * <p>An instance tracks the current state of one stream, starting in
 * {@link Idle}. {@link #apply(StreamEvent)} advances it; an event that is not
 * legal in the current state raises a {@link ProtocolException}. The pure
 * function {@link #next(StreamState, StreamEvent)} is exposed for callers that
 * prefer to manage state themselves.
 */
public final class StreamStateMachine {

    private StreamState state = new Idle();

    public StreamState state() {
        return state;
    }

    /** Applies an event, updates the current state, and returns the new state. */
    public StreamState apply(StreamEvent event) {
        state = next(state, event);
        return state;
    }

    /** Computes the next state for a (state, event) pair without side effects. */
    public static StreamState next(StreamState state, StreamEvent event) {
        return switch (state) {
            case Idle s -> fromIdle(s, event);
            case ReservedLocal s -> fromReservedLocal(s, event);
            case ReservedRemote s -> fromReservedRemote(s, event);
            case Open s -> fromOpen(s, event);
            case HalfClosedLocal s -> fromHalfClosedLocal(s, event);
            case HalfClosedRemote s -> fromHalfClosedRemote(s, event);
            case Closed s -> throw illegal(s, event);
        };
    }

    private static StreamState fromIdle(Idle state, StreamEvent event) {
        return switch (event) {
            case Send(Signal s) -> switch (s) {
                case HEADERS -> new Open();
                case PUSH_PROMISE -> new ReservedLocal();
                default -> throw illegal(state, event);
            };
            case Recv(Signal s) -> switch (s) {
                case HEADERS -> new Open();
                case PUSH_PROMISE -> new ReservedRemote();
                default -> throw illegal(state, event);
            };
        };
    }

    private static StreamState fromReservedLocal(ReservedLocal state, StreamEvent event) {
        return switch (event) {
            case Send(Signal s) -> switch (s) {
                case HEADERS -> new HalfClosedRemote();
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
            case Recv(Signal s) -> switch (s) {
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
        };
    }

    private static StreamState fromReservedRemote(ReservedRemote state, StreamEvent event) {
        return switch (event) {
            case Send(Signal s) -> switch (s) {
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
            case Recv(Signal s) -> switch (s) {
                case HEADERS -> new HalfClosedLocal();
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
        };
    }

    private static StreamState fromOpen(Open state, StreamEvent event) {
        return switch (event) {
            case Send(Signal s) -> switch (s) {
                case END_STREAM -> new HalfClosedLocal();
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
            case Recv(Signal s) -> switch (s) {
                case END_STREAM -> new HalfClosedRemote();
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
        };
    }

    private static StreamState fromHalfClosedLocal(HalfClosedLocal state, StreamEvent event) {
        return switch (event) {
            case Send(Signal s) -> switch (s) {
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
            case Recv(Signal s) -> switch (s) {
                case END_STREAM -> new Closed();
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
        };
    }

    private static StreamState fromHalfClosedRemote(HalfClosedRemote state, StreamEvent event) {
        return switch (event) {
            case Send(Signal s) -> switch (s) {
                case END_STREAM -> new Closed();
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
            case Recv(Signal s) -> switch (s) {
                case RST_STREAM -> new Closed();
                default -> throw illegal(state, event);
            };
        };
    }

    private static ProtocolException illegal(StreamState state, StreamEvent event) {
        String direction = event instanceof Send ? "send" : "receive";
        return new ProtocolException(
                "Cannot %s %s in state %s"
                        .formatted(direction, event.signal(), state.getClass().getSimpleName()));
    }
}
