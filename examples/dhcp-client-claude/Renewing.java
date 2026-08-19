package dhcp;

/**
 * The renewal timer T1 has expired; the client is unicasting DHCPREQUEST to the
 * leasing server to extend its lease (RFC 2131 §4.4.5).
 */
public record Renewing() implements DhcpState {}
