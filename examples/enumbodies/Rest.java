package enumbodies;

/** REST — the knob is released. */
public final class Rest implements Knob {
    @Override
    public Knob next() {
        return Turn.LEFT;
    }
}
