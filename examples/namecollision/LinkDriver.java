package namecollision;

/**
 * Seeds the initial state. The field is typed as the hierarchy and initialised
 * with a concrete state, so the strongest initial-state rule fires — and it must
 * report the same disambiguated id the state carries, not a bare {@code Idle}
 * that matches no state in the machine.
 */
public final class LinkDriver {

    private Link state = new Idle();

    public void advance(Signal signal) {
        this.state = LinkMachine.next(this.state, signal);
    }

    public Link state() {
        return state;
    }
}
