package http2;

/** A frame the local endpoint receives from its peer. */
public record Recv(Signal signal) implements StreamEvent {
}
