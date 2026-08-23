package chaindispatch;

/**
 * An exhaustive fold over {@link Glyph}, written as a chain and committed to a
 * field — every structural signal of a dispatch except the one that matters.
 *
 * <p>It sits beside {@link RelayBoard}, which commits to a field too, on purpose:
 * the two differ only in the declared TYPE of the field written, which is what
 * decides. A name-keyed rule would swallow both.
 */
public final class GlyphNamer {

    private Glyph glyph = new Dot();
    private String label = "";

    public void name() {
        if (glyph instanceof Dot) {
            label = "dot";
        } else if (glyph instanceof Dash) {
            label = "dash";
        }
    }

    public String label() {
        return label;
    }
}
