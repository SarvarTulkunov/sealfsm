package dhcpclaude;

/**
 * The client holds a valid lease and is fully configured. It remains here until
 * the renewal timer T1 expires (RFC 2131 §4.4.5).
 */
public record Bound() implements DhcpState {}
