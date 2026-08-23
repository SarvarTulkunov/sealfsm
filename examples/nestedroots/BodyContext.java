package nestedroots;

/** Holds the current body state and exposes the recognised mutator. */
public final class BodyContext {

    /** Seeds the initial state: a hierarchy-typed field with a concrete initialiser. */
    private Body state = new Empty();

    /** The recognised state mutator: one hierarchy-typed parameter, writes the field. */
    public void setState(Body next) {
        this.state = next;
    }

    public Body state() {
        return state;
    }
}
