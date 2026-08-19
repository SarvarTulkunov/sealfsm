package dhcp;

/**
 * A DHCPOFFER message arrived from a server.
 */
public record OfferReceived() implements DhcpEvent {}
