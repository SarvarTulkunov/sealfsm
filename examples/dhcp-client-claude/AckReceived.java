package dhcpclaude;

/**
 * A DHCPACK message arrived from a server.
 *
 * @param addressInUse the result of the client's duplicate-address check on a
 *                     freshly offered address (RFC 2131 §4.4.1). It is consulted
 *                     only while validating a new offer in the REQUESTING state;
 *                     when extending an existing lease it has no effect.
 */
public record AckReceived(boolean addressInUse) implements DhcpEvent {}
