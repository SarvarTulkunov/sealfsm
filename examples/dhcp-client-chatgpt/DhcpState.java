package dhcp;

/** Represents a state in the DHCP client state-transition diagram. */
public sealed interface DhcpState permits Init, Selecting, Requesting, InitReboot, Rebooting, Bound, Renewing, Rebinding {
}
