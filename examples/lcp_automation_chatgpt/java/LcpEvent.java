package lcpchatgpt;

/** An event defined by the RFC 1661 LCP option-negotiation automaton. */
public enum LcpEvent {
    UP,
    DOWN,
    OPEN,
    CLOSE,
    TO_PLUS,
    TO_MINUS,
    RCR_PLUS,
    RCR_MINUS,
    RCA,
    RCN,
    RTR,
    RTA,
    RUC,
    RXJ_PLUS,
    RXJ_MINUS,
    RXR
}
