package http2;

/**
 * Thrown when a frame is sent or received that is not permitted by the current
 * HTTP/2 stream state. Corresponds to a stream or connection PROTOCOL_ERROR.
 */
public final class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }
}
