package dhcp;

/**
 * The renewal timer T1 expired, prompting the client to begin renewing its lease
 * (RFC 2131 §4.4.5).
 */
public record T1Expired() implements DhcpEvent {}
