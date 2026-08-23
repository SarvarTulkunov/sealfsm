import java.util.List;

/** Implements the RFC 1661 §4.1 LCP option-negotiation state transition table. */
public final class LcpAutomaton {
    private final boolean passiveOnConfigureTimeout;
    private LcpState state = new Initial();

    public LcpAutomaton() {
        this(false);
    }

    public LcpAutomaton(boolean passiveOnConfigureTimeout) {
        this.passiveOnConfigureTimeout = passiveOnConfigureTimeout;
    }

    public LcpState state() {
        return state;
    }

    public LcpTransition on(LcpEvent event) {
        LcpTransition transition = transitionFor(state, event);
        state = transition.state();
        return transition;
    }

    private LcpTransition transitionFor(LcpState state, LcpEvent event) {
        return switch (state) {
            case Initial ignored -> fromInitial(event);
            case Starting ignored -> fromStarting(event);
            case Closed ignored -> fromClosed(event);
            case Stopped ignored -> fromStopped(event);
            case Closing ignored -> fromClosing(event);
            case Stopping ignored -> fromStopping(event);
            case ReqSent ignored -> fromReqSent(event);
            case AckRcvd ignored -> fromAckRcvd(event);
            case AckSent ignored -> fromAckSent(event);
            case Opened ignored -> fromOpened(event);
        };
    }

    private LcpTransition fromInitial(LcpEvent event) {
        return switch (event) {
            case UP -> transition(new Closed(), List.of());
            case OPEN -> transition(new Starting(), List.of(LcpAction.TLS));
            case DOWN, CLOSE, TO_PLUS, TO_MINUS, RCR_PLUS, RCR_MINUS, RCA, RCN, RTR, RTA, RUC, RXJ_PLUS, RXJ_MINUS, RXR -> illegal(new Initial(), event);
        };
    }

    private LcpTransition fromStarting(LcpEvent event) {
        return switch (event) {
            case UP -> transition(new ReqSent(), List.of(LcpAction.IRC, LcpAction.SCR));
            case DOWN -> illegal(new Starting(), event);
            case OPEN -> transition(new Starting(), List.of());
            case CLOSE -> transition(new Initial(), List.of(LcpAction.TLF));
            case TO_PLUS, TO_MINUS, RCR_PLUS, RCR_MINUS, RCA, RCN, RTR, RTA, RUC, RXJ_PLUS, RXJ_MINUS, RXR -> illegal(new Starting(), event);
        };
    }

    private LcpTransition fromClosed(LcpEvent event) {
        return switch (event) {
            case DOWN -> transition(new Initial(), List.of());
            case OPEN -> transition(new ReqSent(), List.of(LcpAction.IRC, LcpAction.SCR));
            case CLOSE -> transition(new Closed(), List.of());
            case RCR_PLUS, RCR_MINUS -> transition(new Closed(), List.of(LcpAction.STA));
            case RCA, RCN, RTR, RUC, RXJ_PLUS, RXJ_MINUS, RXR -> illegal(new Closed(), event);
            case UP, TO_PLUS, TO_MINUS, RTA -> illegal(new Closed(), event);
        };
    }

    private LcpTransition fromStopped(LcpEvent event) {
        return switch (event) {
            case DOWN -> transition(new Starting(), List.of(LcpAction.TLS));
            case OPEN -> transition(new Stopped(), List.of());
            case CLOSE -> transition(new Stopped(), List.of());
            case RCR_PLUS -> transition(new Stopped(), List.of(LcpAction.IRC, LcpAction.SCR, LcpAction.SCA));
            case RCR_MINUS -> transition(new Stopped(), List.of(LcpAction.IRC, LcpAction.SCR, LcpAction.SCN));
            case RCA, RCN -> transition(new Stopped(), List.of());
            case RTR -> transition(new Stopped(), List.of(LcpAction.STA));
            case RTA -> transition(new Stopped(), List.of());
            case RUC -> transition(new Stopped(), List.of(LcpAction.SCJ));
            case RXJ_PLUS -> transition(new Stopped(), List.of());
            case RXJ_MINUS -> transition(new Stopped(), List.of(LcpAction.TLF));
            case RXR -> transition(new Stopped(), List.of());
            case UP, TO_PLUS, TO_MINUS -> illegal(new Stopped(), event);
        };
    }

    private LcpTransition fromClosing(LcpEvent event) {
        return switch (event) {
            case DOWN -> transition(new Closed(), List.of());
            case OPEN -> transition(new Stopping(), List.of());
            case CLOSE -> transition(new Closing(), List.of());
            case TO_PLUS -> transition(new Closing(), List.of(LcpAction.STR));
            case TO_MINUS -> transition(new Closed(), List.of(LcpAction.TLF));
            case RCR_PLUS, RCR_MINUS, RCA, RCN -> transition(new Closing(), List.of());
            case RTR -> transition(new Closing(), List.of(LcpAction.STA));
            case RTA -> transition(new Closed(), List.of(LcpAction.TLF));
            case RUC -> transition(new Closing(), List.of(LcpAction.SCJ));
            case RXJ_PLUS -> transition(new Closing(), List.of());
            case RXJ_MINUS -> transition(new Closed(), List.of(LcpAction.TLF));
            case RXR -> transition(new Closing(), List.of());
            case UP -> illegal(new Closing(), event);
        };
    }

    private LcpTransition fromStopping(LcpEvent event) {
        return switch (event) {
            case DOWN -> transition(new Initial(), List.of());
            case OPEN -> transition(new Stopping(), List.of());
            case CLOSE -> transition(new Closing(), List.of());
            case TO_PLUS -> transition(new Stopping(), List.of(LcpAction.STR));
            case TO_MINUS -> transition(new Stopped(), List.of(LcpAction.TLF));
            case RCR_PLUS, RCR_MINUS, RCA, RCN -> transition(new Stopping(), List.of());
            case RTR -> transition(new Stopping(), List.of(LcpAction.STA));
            case RTA -> transition(new Stopped(), List.of(LcpAction.TLF));
            case RUC -> transition(new Stopping(), List.of(LcpAction.SCJ));
            case RXJ_PLUS -> transition(new Stopping(), List.of());
            case RXJ_MINUS -> transition(new Stopped(), List.of(LcpAction.TLF));
            case RXR -> transition(new Stopping(), List.of());
            case UP -> illegal(new Stopping(), event);
        };
    }

    private LcpTransition fromReqSent(LcpEvent event) {
        return switch (event) {
            case DOWN -> transition(new Starting(), List.of());
            case OPEN -> transition(new ReqSent(), List.of());
            case CLOSE -> transition(new Closing(), List.of(LcpAction.IRC, LcpAction.STR));
            case TO_PLUS -> transition(new ReqSent(), List.of(LcpAction.SCR));
            case TO_MINUS -> configureTimeoutExpired();
            case RCR_PLUS -> transition(new AckSent(), List.of(LcpAction.SCA));
            case RCR_MINUS -> transition(new ReqSent(), List.of(LcpAction.SCN));
            case RCA -> transition(new AckRcvd(), List.of(LcpAction.IRC));
            case RCN -> transition(new ReqSent(), List.of(LcpAction.IRC, LcpAction.SCR));
            case RTR -> transition(new ReqSent(), List.of(LcpAction.STA));
            case RTA -> transition(new ReqSent(), List.of());
            case RUC -> transition(new ReqSent(), List.of(LcpAction.SCJ));
            case RXJ_PLUS -> transition(new ReqSent(), List.of());
            case RXJ_MINUS -> transition(new Stopped(), List.of(LcpAction.TLF));
            case RXR -> transition(new ReqSent(), List.of());
            case UP -> illegal(new ReqSent(), event);
        };
    }

    private LcpTransition fromAckRcvd(LcpEvent event) {
        return switch (event) {
            case DOWN -> transition(new Starting(), List.of());
            case OPEN -> transition(new AckRcvd(), List.of());
            case CLOSE -> transition(new Closing(), List.of(LcpAction.IRC, LcpAction.STR));
            case TO_PLUS -> transition(new AckRcvd(), List.of(LcpAction.SCR));
            case TO_MINUS -> transition(new Stopped(), List.of(LcpAction.TLF));
            case RCR_PLUS -> transition(new Opened(), List.of(LcpAction.SCA, LcpAction.TLU));
            case RCR_MINUS -> transition(new AckRcvd(), List.of(LcpAction.SCN));
            case RCA -> transition(new ReqSent(), List.of(LcpAction.SCR));
            case RCN -> transition(new ReqSent(), List.of(LcpAction.SCR));
            case RTR -> transition(new AckRcvd(), List.of(LcpAction.STA));
            case RTA -> transition(new AckRcvd(), List.of());
            case RUC -> transition(new AckRcvd(), List.of(LcpAction.SCJ));
            case RXJ_PLUS -> transition(new AckRcvd(), List.of());
            case RXJ_MINUS -> transition(new Stopped(), List.of(LcpAction.TLF));
            case RXR -> transition(new AckRcvd(), List.of());
            case UP -> illegal(new AckRcvd(), event);
        };
    }

    private LcpTransition fromAckSent(LcpEvent event) {
        return switch (event) {
            case DOWN -> transition(new Starting(), List.of());
            case OPEN -> transition(new AckSent(), List.of());
            case CLOSE -> transition(new Closing(), List.of(LcpAction.IRC, LcpAction.STR));
            case TO_PLUS -> transition(new AckSent(), List.of(LcpAction.SCR));
            case TO_MINUS -> configureTimeoutExpired();
            case RCR_PLUS -> transition(new AckSent(), List.of(LcpAction.SCA));
            case RCR_MINUS -> transition(new AckSent(), List.of(LcpAction.SCN));
            case RCA -> transition(new Opened(), List.of(LcpAction.IRC, LcpAction.TLU));
            case RCN -> transition(new AckSent(), List.of(LcpAction.IRC, LcpAction.SCR));
            case RTR -> transition(new AckSent(), List.of(LcpAction.STA));
            case RTA -> transition(new Opened(), List.of());
            case RUC -> transition(new AckSent(), List.of(LcpAction.SCJ));
            case RXJ_PLUS -> transition(new AckSent(), List.of());
            case RXJ_MINUS -> transition(new Stopped(), List.of(LcpAction.TLF));
            case RXR -> transition(new AckSent(), List.of());
            case UP -> illegal(new AckSent(), event);
        };
    }

    private LcpTransition fromOpened(LcpEvent event) {
        return switch (event) {
            case DOWN -> transition(new Starting(), List.of(LcpAction.TLD));
            case OPEN -> transition(new Opened(), List.of());
            case CLOSE -> transition(new Closing(), List.of(LcpAction.TLD, LcpAction.IRC, LcpAction.STR));
            case TO_PLUS, TO_MINUS -> illegal(new Opened(), event);
            case RCR_PLUS -> transition(new AckSent(), List.of(LcpAction.TLD, LcpAction.SCR, LcpAction.SCA));
            case RCR_MINUS -> transition(new ReqSent(), List.of(LcpAction.TLD, LcpAction.SCR, LcpAction.SCN));
            case RCA, RCN -> transition(new ReqSent(), List.of(LcpAction.TLD, LcpAction.SCR));
            case RTR -> transition(new Stopping(), List.of(LcpAction.TLD, LcpAction.ZRC, LcpAction.STA));
            case RTA -> transition(new ReqSent(), List.of(LcpAction.TLD, LcpAction.SCR));
            case RUC -> transition(new Opened(), List.of(LcpAction.SCJ));
            case RXJ_PLUS -> transition(new Opened(), List.of());
            case RXJ_MINUS -> transition(new Stopping(), List.of(LcpAction.TLD, LcpAction.IRC, LcpAction.STR));
            case RXR -> transition(new Opened(), List.of(LcpAction.SER));
            case UP -> illegal(new Opened(), event);
        };
    }

    private LcpTransition configureTimeoutExpired() {
        return transition(new Stopped(), passiveOnConfigureTimeout ? List.of() : List.of(LcpAction.TLF));
    }

    private LcpTransition transition(LcpState state, List<LcpAction> actions) {
        return new LcpTransition(state, actions);
    }

    private LcpTransition illegal(LcpState state, LcpEvent event) {
        throw new IllegalLcpEventException(state, event);
    }
}
