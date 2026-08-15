package examples.gofcontext;

/**
 * The GoF {@code Context}: holds the current state and exposes the mutator the
 * state classes call. The field initializer pins the initial state to
 * {@code Closed} (same initial-state heuristic as {@code examples/door}).
 */
public final class PortalContext {

    private Portal state = new Closed();

    /** The recognised state mutator: single hierarchy-typed param, writes the field. */
    public void setState(Portal next) {
        this.state = next;
    }

    public void handle(Event event) {
        state.handle(this, event);
    }

    public Portal state() {
        return state;
    }
}
