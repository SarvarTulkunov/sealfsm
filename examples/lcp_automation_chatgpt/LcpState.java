/**
 * A state of the RFC 1661 Link Control Protocol option-negotiation automaton.
 */
public sealed interface LcpState permits Initial, Starting, Closed, Stopped, Closing, Stopping, ReqSent, AckRcvd, AckSent, Opened {
}
