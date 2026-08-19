package http2stream;

/**
 * Both peers can use a stream in the open state to send frames of any type.
 */
public record Open() implements State {}
