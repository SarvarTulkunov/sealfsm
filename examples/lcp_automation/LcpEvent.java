package lcp;

/**
 * Represents an event processed by the PPP LCP state machine.
 * Events carry protocol constraints explicitly as typed record fields.
 */
public sealed interface LcpEvent {
    record Up() implements LcpEvent {}
    record Down() implements LcpEvent {}
    record Open() implements LcpEvent {}
    record Close() implements LcpEvent {}
    
    /** Encodes RFC events TO+ (counter > 0) and TO- (counter == 0). */
    record Timeout(int counter) implements LcpEvent {}
    
    /** Encodes RFC events RCR+ (acceptable=true) and RCR- (acceptable=false). */
    record ReceiveConfigureRequest(boolean acceptable) implements LcpEvent {}
    record ReceiveConfigureAck() implements LcpEvent {}
    record ReceiveConfigureNakRej() implements LcpEvent {}
    record ReceiveTerminateRequest() implements LcpEvent {}
    record ReceiveTerminateAck() implements LcpEvent {}
    record ReceiveUnknownCode() implements LcpEvent {}
    
    /** Encodes RFC events RXJ+ (catastrophic=false) and RXJ- (catastrophic=true). */
    record ReceiveCodeReject(boolean catastrophic) implements LcpEvent {}

    /**
     * RFC event RXR — Receive-Echo-Request, Receive-Echo-Reply or
     * Receive-Discard-Request. Added by hand to complete §4.1; see the note in
     * {@code PppLcpStateMachine}.
     */
    record ReceiveEchoRequest() implements LcpEvent {}
}