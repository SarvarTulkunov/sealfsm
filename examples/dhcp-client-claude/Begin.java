package dhcpclaude;

/**
 * The client begins the configuration process from an initial state, leaving
 * INIT (by sending DHCPDISCOVER) or INIT-REBOOT (by sending DHCPREQUEST).
 */
public record Begin() implements DhcpEvent {}
