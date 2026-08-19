package dhcp;

/** The client is rebinding its lease by contacting any available DHCP server. */
public record Rebinding() implements DhcpState {
}
