package mutatorshape;

/**
 * Two methods with identical signatures — one {@code Vent} in, nothing out, a
 * {@code Vent} field written — that mean opposite things.
 */
public final class VentRig {

    private Vent state = new Sealed();

    private String audit = "";

    /**
     * THE MUTATOR: the parameter is what lands in the field. Named the same as
     * {@link BoltRig#assume} on purpose — a different hierarchy's mutator sharing
     * the spelling must not let its call sites be claimed here.
     */
    public void assume(Vent next) {
        this.state = next;
    }

    /**
     * NOT a mutator, though it passes "one hierarchy-typed parameter, writes a
     * hierarchy-typed field". The parameter is the state being left; the successor
     * is chosen here. So the argument at a call site says nothing about where the
     * machine goes, and reading it as the target reported {@code Venting ->
     * Venting} — resolved, and wrong.
     */
    public void restart(Vent previous) {
        this.audit = previous.toString();
        this.state = new Sealed();
    }

    public Vent state() {
        return state;
    }

    public String audit() {
        return audit;
    }
}
