package rootseed;

/** A driver field that agrees with {@link Gate#CLOSED}. */
public final class Yard {

    private Gate gate = new Gate.Closed();

    public void swing() {
        gate = gate.next();
    }
}
