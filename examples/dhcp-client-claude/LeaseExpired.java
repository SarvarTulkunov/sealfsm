package dhcp;

/**
 * The lease expired before it could be renewed or rebound, forcing the client
 * to halt network processing and restart configuration (RFC 2131 §4.4.5).
 */
public record LeaseExpired() implements DhcpEvent {}
