package dhcpclaude;

/**
 * The client has sent a DHCPREQUEST to confirm a cached address and is awaiting
 * a DHCPACK or DHCPNAK (RFC 2131 §4.4.2).
 */
public record Rebooting() implements DhcpState {}
