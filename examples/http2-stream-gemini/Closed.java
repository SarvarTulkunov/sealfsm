package http2stream;

/**
 * The terminal state. The stream cannot be used for further frame exchanges.
 */
public record Closed() implements State {}
