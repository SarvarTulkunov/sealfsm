package dhcp;

/**
 * The alternate initial state used when the client remembers a previously
 * assigned address and wants to verify it, broadcasting a DHCPREQUEST for that
 * address (RFC 2131 §4.4.2).
 */
public record InitReboot() implements DhcpState {}
