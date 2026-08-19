package dhcp;

/**
 * The client selects one of the collected DHCPOFFER messages and requests it by
 * broadcasting a DHCPREQUEST (RFC 2131 §4.4.1).
 */
public record SelectOffer() implements DhcpEvent {}
