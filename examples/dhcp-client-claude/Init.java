package dhcp;

/**
 * The initial state for acquiring a new lease. The client has no valid
 * configuration and broadcasts a DHCPDISCOVER to locate servers (RFC 2131
 * §4.4.1).
 */
public record Init() implements DhcpState {}
