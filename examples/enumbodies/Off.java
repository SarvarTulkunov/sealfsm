package enumbodies;

/** OFF — the resting state. */
public final class Off implements Lamp {
    @Override
    public Lamp press() {
        return Lit.DIM;
    }
}
