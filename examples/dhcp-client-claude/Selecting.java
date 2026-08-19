package dhcpclaude;

/**
 * The client has broadcast a DHCPDISCOVER and is collecting DHCPOFFER messages
 * from one or more servers before selecting one (RFC 2131 §4.4.1).
 */
public record Selecting() implements DhcpState {}
