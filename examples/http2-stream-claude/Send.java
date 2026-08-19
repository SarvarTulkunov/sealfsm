package http2;

/** A frame the local endpoint sends. */
public record Send(Signal signal) implements StreamEvent {
}
