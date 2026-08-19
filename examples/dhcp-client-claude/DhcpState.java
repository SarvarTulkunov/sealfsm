package dhcp;

/**
 * Lifecycle state of a DHCP client, following the state-transition diagram in
 * RFC 2131 §4.4 (Figure 5).
 *
 * <p>A client normally begins in {@link Init} (or {@link InitReboot} when it has
 * a cached address to verify). The machine is cyclic: a bound client renews and
 * rebinds its lease, and any failure or expiry returns it to {@link Init}. There
 * is no terminal state.
 */
public sealed interface DhcpState
        permits Init, InitReboot, Selecting, Requesting, Rebooting,
                Bound, Renewing, Rebinding {
}
