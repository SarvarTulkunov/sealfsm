package dhcpclaude;

/**
 * The client has selected an offer and broadcast a DHCPREQUEST for it, awaiting
 * the selected server's DHCPACK or DHCPNAK (RFC 2131 §4.4.1).
 */
public record Requesting() implements DhcpState {}
