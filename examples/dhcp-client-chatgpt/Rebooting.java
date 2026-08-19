package dhcp;

/** The client is waiting for the result of its DHCPREQUEST during address verification. */
public record Rebooting() implements DhcpState {
}
