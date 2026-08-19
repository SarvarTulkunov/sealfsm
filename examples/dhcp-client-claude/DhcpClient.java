package dhcp;

/**
 * A DHCP client driven through the state machine of RFC 2131 §4.4 (Figure 5).
 *
 * <p>A client starts in {@link Init}; use {@link #withCachedAddress()} to start
 * in {@link InitReboot} instead. {@link #apply(DhcpEvent)} advances the current
 * state, and an event that has no transition from the current state raises an
 * {@link IllegalStateException}. {@link #transition(DhcpState, DhcpEvent)}
 * exposes the same logic as a pure function.
 */
public final class DhcpClient {

    private DhcpState state;

    private DhcpClient(DhcpState initialState) {
        this.state = initialState;
    }

    /** Creates a client in the INIT state, ready to acquire a new lease. */
    public DhcpClient() {
        this(new Init());
    }

    /** Creates a client in the INIT-REBOOT state, ready to verify a cached address. */
    public static DhcpClient withCachedAddress() {
        return new DhcpClient(new InitReboot());
    }

    public DhcpState state() {
        return state;
    }

    /** Applies an event, updates the current state, and returns the new state. */
    public DhcpState apply(DhcpEvent event) {
        state = transition(state, event);
        return state;
    }

    /** Computes the next state for a (state, event) pair without side effects. */
    public static DhcpState transition(DhcpState state, DhcpEvent event) {
        return switch (state) {
            case Init s -> fromInit(s, event);
            case InitReboot s -> fromInitReboot(s, event);
            case Selecting s -> fromSelecting(s, event);
            case Requesting s -> fromRequesting(s, event);
            case Rebooting s -> fromRebooting(s, event);
            case Bound s -> fromBound(s, event);
            case Renewing s -> fromRenewing(s, event);
            case Rebinding s -> fromRebinding(s, event);
        };
    }

    private static DhcpState fromInit(Init state, DhcpEvent event) {
        return switch (event) {
            case Begin ignored -> new Selecting();          // broadcast DHCPDISCOVER
            default -> throw illegalTransition(state, event);
        };
    }

    private static DhcpState fromInitReboot(InitReboot state, DhcpEvent event) {
        return switch (event) {
            case Begin ignored -> new Rebooting();          // broadcast DHCPREQUEST for cached address
            default -> throw illegalTransition(state, event);
        };
    }

    private static DhcpState fromSelecting(Selecting state, DhcpEvent event) {
        return switch (event) {
            case OfferReceived ignored -> new Selecting();  // collect replies
            case SelectOffer ignored -> new Requesting();   // send DHCPREQUEST for the chosen offer
            default -> throw illegalTransition(state, event);
        };
    }

    private static DhcpState fromRequesting(Requesting state, DhcpEvent event) {
        return switch (event) {
            case OfferReceived ignored -> new Requesting(); // additional offer, discard
            case AckReceived(boolean addressInUse) when addressInUse ->
                new Init();                                 // address already in use: send DHCPDECLINE, restart
            case AckReceived ignored -> new Bound();        // commit lease, set timers T1, T2
            case NakReceived ignored -> new Init();         // request refused, restart
            default -> throw illegalTransition(state, event);
        };
    }

    private static DhcpState fromRebooting(Rebooting state, DhcpEvent event) {
        return switch (event) {
            case AckReceived ignored -> new Bound();        // cached address confirmed, set timers T1, T2
            case NakReceived ignored -> new Init();         // cached address rejected, restart
            default -> throw illegalTransition(state, event);
        };
    }

    private static DhcpState fromBound(Bound state, DhcpEvent event) {
        return switch (event) {
            case OfferReceived ignored -> new Bound();      // unsolicited, discard
            case AckReceived ignored -> new Bound();        // unsolicited, discard
            case NakReceived ignored -> new Bound();        // unsolicited, discard
            case T1Expired ignored -> new Renewing();       // unicast DHCPREQUEST to the leasing server
            default -> throw illegalTransition(state, event);
        };
    }

    private static DhcpState fromRenewing(Renewing state, DhcpEvent event) {
        return switch (event) {
            case AckReceived ignored -> new Bound();        // lease extended, reset timers T1, T2
            case T2Expired ignored -> new Rebinding();      // broadcast DHCPREQUEST to any server
            case NakReceived ignored -> new Init();         // halt network, restart
            default -> throw illegalTransition(state, event);
        };
    }

    private static DhcpState fromRebinding(Rebinding state, DhcpEvent event) {
        return switch (event) {
            case AckReceived ignored -> new Bound();        // lease extended, reset timers T1, T2
            case NakReceived ignored -> new Init();         // halt network, restart
            case LeaseExpired ignored -> new Init();        // halt network, restart
            default -> throw illegalTransition(state, event);
        };
    }

    private static IllegalStateException illegalTransition(DhcpState state, DhcpEvent event) {
        return new IllegalStateException(
            "No DHCP transition for %s in %s".formatted(
                event.getClass().getSimpleName(), state.getClass().getSimpleName()));
    }
}
