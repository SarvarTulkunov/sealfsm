package dhcp;

/**
 * Drives one typical DHCP client path: acquiring a new lease and then renewing
 * it once the T1 timer fires.
 */
public final class Main {

    public static void main(String[] args) {
        DhcpClient client = new DhcpClient();
        System.out.printf("%-20s -> %s%n", "initial", client.state().getClass().getSimpleName());

        expect(client.apply(new Begin()), Selecting.class, "begin");
        expect(client.apply(new OfferReceived()), Selecting.class, "recv DHCPOFFER");
        expect(client.apply(new SelectOffer()), Requesting.class, "select offer");
        expect(client.apply(new AckReceived(false)), Bound.class, "recv DHCPACK");
        expect(client.apply(new T1Expired()), Renewing.class, "T1 expires");
        expect(client.apply(new AckReceived(false)), Bound.class, "recv DHCPACK");

        System.out.println("Acquired and renewed a lease successfully.");
    }

    private static void expect(DhcpState actual, Class<? extends DhcpState> expected, String step) {
        if (!expected.isInstance(actual)) {
            throw new AssertionError(
                step + ": expected " + expected.getSimpleName() + " but was " + actual);
        }
        System.out.printf("%-20s -> %s%n", step, actual.getClass().getSimpleName());
    }
}
