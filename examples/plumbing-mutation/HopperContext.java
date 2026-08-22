package plumbingmutation;

/** Holds the current state and exposes the recognised mutator. */
public final class HopperContext {

    private Hopper state = new Empty();

    /** The recognised state mutator: single hierarchy-typed param, writes the field. */
    public void setState(Hopper next) {
        this.state = next;
    }

    public Hopper state() {
        return state;
    }
}
