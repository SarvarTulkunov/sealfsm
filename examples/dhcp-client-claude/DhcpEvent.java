package dhcp;

/**
 * An input that drives the DHCP client between states: a message received from a
 * server, a timer expiry, or a local decision by the client.
 */
public sealed interface DhcpEvent
        permits Begin, SelectOffer, OfferReceived, AckReceived,
                NakReceived, T1Expired, T2Expired, LeaseExpired {
}
