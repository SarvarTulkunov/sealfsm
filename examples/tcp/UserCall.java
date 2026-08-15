package tcp;

/** Calls issued by the local application (RFC 9293 §3.9.1). */
public enum UserCall implements Event {
    PASSIVE_OPEN, ACTIVE_OPEN, SEND, CLOSE, ABORT
}
