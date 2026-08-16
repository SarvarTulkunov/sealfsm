package http2;

/**
 * An event applied to a stream: a frame the local endpoint either sends or
 * receives, reduced to the {@link Signal} relevant to the stream lifecycle.
 */
public sealed interface StreamEvent permits Send, Recv {

    Signal signal();
}
