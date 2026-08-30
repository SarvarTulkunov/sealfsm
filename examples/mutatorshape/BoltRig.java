package mutatorshape;

import java.util.Objects;

/**
 * Holds the current {@code Bolt} and exposes the three methods F22 has to tell
 * apart. All three have exactly one hierarchy-typed parameter, so no signature
 * separates them — only what the body does with it.
 */
public final class BoltRig {

    /** Seeds the initial state: a hierarchy-typed field with a concrete initialiser. */
    private Bolt state = new Idle();

    private String log = "";

    /**
     * THE MUTATOR, recognised by shape alone: the parameter is what lands in the
     * hierarchy-typed field. Its name is on no list of conventional setter words,
     * which is the point — a machine written by someone who says {@code assume}
     * rather than {@code setState} is not a different machine.
     */
    public void assume(Bolt next) {
        this.state = next;
    }

    /**
     * NEGATIVE CONTROL for the narrowing, in the direction that costs edges. The
     * commit is <em>laundered</em> through a null check, so the assigned expression
     * is not literally the parameter. It is still committed FROM the parameter and
     * from nothing else, so it must still be recognised: a rule that demanded a
     * bare parameter read would drop every call site of a defensive mutator, and
     * drop it silently — the F10 rule ignores an expression statement no commit
     * form claims, so there would be no unresolved marker to notice.
     */
    public void engage(Bolt next) {
        this.state = Objects.requireNonNull(next, "next");
    }

    /**
     * THE TRAP the word list sprang: a conventional mutator NAME on a method that
     * commits nothing. One {@code Bolt} parameter, read only to describe it. Under
     * the old rule the name alone made this a mutator, and every {@code become(x)}
     * call site published {@code x} as a resolved successor. It must contribute no
     * edge at all — not an unresolved one either, because nothing here is a
     * transition whose target went unrecovered.
     */
    public void become(Bolt observed) {
        this.log = observed.toString();
    }

    public Bolt state() {
        return state;
    }

    public String log() {
        return log;
    }
}
