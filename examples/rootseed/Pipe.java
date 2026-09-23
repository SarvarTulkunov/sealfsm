package rootseed;

/** Starts from the root constant: a field read, which rule 1 does not see. */
public final class Pipe {

    private Valve state = Valve.SHUT;

    public void cycle() {
        state = state.next();
    }
}
