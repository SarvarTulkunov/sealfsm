package dhcp;

/**
 * A DHCPNAK message arrived from a server, refusing the client's request.
 */
public record NakReceived() implements DhcpEvent {}
