package dhcpclaude;

/**
 * The rebinding timer T2 expired, prompting the client to begin rebinding its
 * lease with any server (RFC 2131 §4.4.5).
 */
public record T2Expired() implements DhcpEvent {}
