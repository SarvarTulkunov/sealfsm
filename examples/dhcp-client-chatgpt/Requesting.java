package dhcp;

/** The client has selected a DHCP offer and is requesting the offered configuration. */
public record Requesting() implements DhcpState {
}
