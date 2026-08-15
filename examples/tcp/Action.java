package tcp;

/**
 * Side effects the state machine asks the driver to perform on a transition
 * (RFC 9293 §3.10). The FSM itself performs no I/O — it only names actions.
 */
public enum Action {
    CREATE_TCB, DELETE_TCB,
    SND_SYN, SND_SYN_ACK, SND_ACK, SND_FIN, SND_RST,
    SIGNAL_CONNECTION_OPEN, SIGNAL_CONNECTION_CLOSING, SIGNAL_ABORT,
    START_TIME_WAIT_TIMER,
    NONE
}
