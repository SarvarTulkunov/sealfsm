package dhcp;

/** Implements the DHCP client state transitions from RFC 2131 Figure 5. */
public final class DhcpClientStateMachine {
    private DhcpState state = new Init();

    public DhcpState state() {
        return state;
    }

    public DhcpState on(DhcpEvent event) {
        state = nextState(state, event);
        return state;
    }

    public void startWithExistingAddress() {
        if (!(state instanceof Init)) {
            throw new IllegalStateException("Existing address can only be supplied from INIT");
        }
        state = new InitReboot();
    }

    private static DhcpState nextState(DhcpState state, DhcpEvent event) {
        return switch (state) {
            case Init ignored -> switch (event) {
                case DHCPDISCOVER_SENT -> new Selecting();
                default -> invalid(state, event);
            };
            case Selecting ignored -> switch (event) {
                case DHCPOFFER_RECEIVED -> new Selecting();
                case SELECT_OFFER -> new Requesting();
                case DHCPNAK_RECEIVED -> new Selecting();
                default -> invalid(state, event);
            };
            case Requesting ignored -> switch (event) {
                case DHCPACK_RECEIVED -> new Bound();
                case DHCPNAK_RECEIVED -> new Init();
                case DHCPOFFER_RECEIVED -> new Requesting();
                default -> invalid(state, event);
            };
            case InitReboot ignored -> switch (event) {
                case DHCPREQUEST_SENT -> new Rebooting();
                case DHCPNAK_RECEIVED -> new Init();
                default -> invalid(state, event);
            };
            case Rebooting ignored -> switch (event) {
                case DHCPACK_RECEIVED -> new Bound();
                case DHCPNAK_RECEIVED -> new Init();
                case DHCPOFFER_RECEIVED -> new Rebooting();
                default -> invalid(state, event);
            };
            case Bound ignored -> switch (event) {
                case DHCPOFFER_RECEIVED, DHCPACK_RECEIVED, DHCPNAK_RECEIVED -> new Bound();
                case T1_EXPIRED -> new Renewing();
                default -> invalid(state, event);
            };
            case Renewing ignored -> switch (event) {
                case DHCPACK_RECEIVED -> new Bound();
                case T2_EXPIRED -> new Rebinding();
                case DHCPNAK_RECEIVED, LEASE_EXPIRED -> new Init();
                default -> invalid(state, event);
            };
            case Rebinding ignored -> switch (event) {
                case DHCPACK_RECEIVED -> new Bound();
                case DHCPNAK_RECEIVED, LEASE_EXPIRED -> throw new NetworkHaltedException();
                default -> invalid(state, event);
            };
        };
    }

    private static DhcpState invalid(DhcpState state, DhcpEvent event) {
        throw new IllegalStateException(
                "Invalid transition from " + state.getClass().getSimpleName() + " on " + event);
    }

    public static final class NetworkHaltedException extends IllegalStateException {
        public NetworkHaltedException() {
            super("DHCP client must halt network processing");
        }
    }

    public static void main(String[] args) {
        var machine = new DhcpClientStateMachine();

        machine.on(DhcpEvent.DHCPDISCOVER_SENT);
        machine.on(DhcpEvent.DHCPOFFER_RECEIVED);
        machine.on(DhcpEvent.SELECT_OFFER);
        machine.on(DhcpEvent.DHCPACK_RECEIVED);
        machine.on(DhcpEvent.T1_EXPIRED);
        machine.on(DhcpEvent.T2_EXPIRED);
        machine.on(DhcpEvent.DHCPACK_RECEIVED);

        System.out.println(machine.state());
    }
}
