package dhcp;

/** The client is renewing its lease with the DHCP server that supplied it. */
public record Renewing() implements DhcpState {
}
