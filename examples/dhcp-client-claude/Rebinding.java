package dhcpclaude;

/**
 * The rebinding timer T2 has expired; the client is broadcasting DHCPREQUEST to
 * any reachable server to extend its lease (RFC 2131 §4.4.5).
 */
public record Rebinding() implements DhcpState {}
