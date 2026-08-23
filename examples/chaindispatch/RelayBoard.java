package chaindispatch;

/**
 * The dominant pre-pattern-matching dispatch shape: a chain of {@code instanceof}
 * tests over a <em>field</em>, committing by writing that field back.
 *
 * <p>Nothing here is a signature the tool could key on. {@code accept} takes no
 * hierarchy-typed parameter, so the signature-based centralized recognizer never
 * looked at it; it holds no {@code switch}, so the switch recognizer found
 * nothing; and it is not declared on the hierarchy, so the distributed recognizer
 * skipped it too. What makes it a dispatch is that the selector's dynamic type is
 * discriminated across the {@code permits} clause and each branch installs a
 * successor — which is exactly what a {@code switch} over the state does, spelled
 * with {@code if}.
 *
 * <p>The event tests are <em>nested inside</em> each branch rather than conjoined
 * with the type test. That is how this shape is normally written, and it is why
 * the labels below come out as guards: the type test is consumed as the arm label
 * (it must not also appear as a predicate — the edge is unconditional in that
 * state), while an ordinary {@code if} inside the branch is walked as an ordinary
 * condition. {@link ShutterLogic} holds the other spelling, where the event test
 * is a conjunct of the type test and does become a Σ label.
 */
public final class RelayBoard {

    private Relay state = new Idle();

    public Relay state() {
        return state;
    }

    /** Idle --CLOSE--> Live --OPEN--> Idle, --FAULT--> Tripped --CLEAR--> Idle. */
    public Relay accept(Pulse pulse) {
        if (state instanceof Idle) {
            if (pulse == Pulse.CLOSE) {
                state = new Live();
            }
        } else if (state instanceof Live) {
            if (pulse == Pulse.OPEN) {
                state = new Idle();
            } else if (pulse == Pulse.FAULT) {
                state = new Tripped();
            }
        } else if (state instanceof Tripped) {
            if (pulse == Pulse.CLEAR) {
                state = new Idle();
            }
        }
        return state;
    }

    /**
     * NEGATIVE CONTROL for the two-branch rule, and the one place this fixture
     * records a cost rather than a gain: a single type test contributes <b>no
     * edge</b>, even though it commits an H value and even though
     * {@code Tripped -> Idle} is a transition the program can make.
     *
     * <p>The rule is that discriminating <em>between</em> states is what makes a
     * dispatch a dispatch. One type test is a check — the shape appears constantly
     * for reasons that have nothing to do with succession — and an {@code if} is
     * far too common a construct to read every committing one as an automaton. The
     * same threshold, for the same reason, already gates the carrier path:
     * {@code CarrierTransitionDetector.qualifies} wants carrier methods on two
     * permitted subtypes because "one producing subtype is more consistent with a
     * factory or a normaliser than with an automaton".
     *
     * <p>Pinned here so the threshold is a decision with a visible price rather
     * than an unexamined default: move it and this fixture's edge count changes.
     */
    public void reset() {
        if (state instanceof Tripped) {
            state = new Idle();
        }
    }
}
