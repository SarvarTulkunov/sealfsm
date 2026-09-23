package enumbodies;

/** SHUT — nothing flows. */
public final class Shut implements Valve {
    @Override
    public Valve toggle() {
        return new Open.Half();
    }
}
