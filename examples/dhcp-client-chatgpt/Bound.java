package dhcp;

/** The client has a valid lease and is using the bound network configuration. */
public record Bound() implements DhcpState {
}
