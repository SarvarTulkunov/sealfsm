package throwcarrier;

/**
 * Driver, present only to seed both machines' initial states through the
 * hierarchy-typed-field rule. It discriminates nothing and commits nothing, so it
 * is not itself a transition function under any recognizer.
 */
public final class Deck {

    private Capstan capstan = new Slack();
    private Hoist hoist = new Parked();

    /** Apply {@code order} to the capstan, keeping whatever it hauls to. */
    public void haul(Order order) {
        capstan = capstan.on(order).next();
    }

    /** Apply {@code lever} to the hoist, keeping the state when one is produced. */
    public void lift(Lever lever) {
        hoist = hoist.on(lever).orElse(hoist);
    }

    public Capstan capstan() {
        return capstan;
    }

    public Hoist hoist() {
        return hoist;
    }
}
