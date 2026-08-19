package websocket;

/**
 * A subset of the WebSocket close status codes defined in RFC 6455 §7.4.1.
 */
public final class CloseCodes {

    /** 1000: normal closure; the purpose the connection was established for has been fulfilled. */
    public static final int NORMAL_CLOSURE = 1000;

    /** 1005: no status code was actually present (reserved; MUST NOT be sent on the wire). */
    public static final int NO_STATUS_RECEIVED = 1005;

    /** 1006: connection closed abnormally, with no Close frame (reserved; MUST NOT be sent on the wire). */
    public static final int ABNORMAL_CLOSURE = 1006;

    private CloseCodes() {
    }
}
