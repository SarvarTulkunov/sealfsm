package statefuldriver;

/**
 * The shape the finding is about, beside the negative controls that decide how far
 * the recognizer may be widened.
 *
 * <p>{@code step} is a transition function by any reading: it takes an input,
 * consults the state, produces the successor. It simply never says so in its
 * signature. The dispatch is a {@code switch} statement with colon arms — the
 * spelling of every state table written before pattern-matching switch — so its
 * result is committed by each arm rather than at the switch, and nothing about it
 * is visible to a detector that inspects the switch's parent.
 *
 * <h2>Why "returns H" cannot be the whole rule</h2>
 * Deleting the parameter requirement with nothing in its place admits every method
 * in the model whose codomain happens to be {@code Sash}, and the extractor then
 * walks each one as a standalone dispatch — reporting a successor whose source
 * state it has no way to name. The two controls below are exactly those, and they
 * are here rather than in a package of their own because the point is that they
 * are <em>indistinguishable from {@code step} by signature</em>: same return type,
 * same absent parameter, same declaring class.
 *
 * <ul>
 *   <li>{@link #state()} — an accessor. It returns the root-typed field bare, and
 *       {@code TransitionResolver} deliberately refuses to read a root-typed field
 *       as a self-loop (such a field is a value with its own identity), so
 *       admitting it contributes an unresolved edge out of a state that does not
 *       exist, against a machine that is otherwise 6/6.</li>
 *   <li>{@link #initial()} — a factory, and the worse of the two: its target
 *       {@code Seated} is perfectly resolvable, so what it contributes is a real
 *       successor attributed to a source the analysis never established.</li>
 * </ul>
 *
 * <p>What separates {@code step} from both is not its codomain but that it
 * <em>discriminates</em> the state. That is the property the parameter was
 * standing in for all along, and asking for it directly is what lets the parameter
 * go.
 *
 * <p>{@link #describe()} is the third control and pins the other half: it
 * discriminates the state exactly as {@code step} does and differs only in
 * codomain. It stays rejected — widening what counts as an <em>input</em> must not
 * weaken what counts as a <em>commit</em>, which is the whole precision argument of
 * {@code DispatchCommitDetector} and of {@code examples/foreignfold}.
 */
public final class SashDriver {

    private Sash state = new Seated();

    /**
     * Seated --LIFT--> Parted --LIFT--> Raised --JAR--> Seated, with each arm's
     * {@code default} folding back. 6 edges, all resolved.
     */
    public Sash step(Nudge nudge) {
        switch (state) {
            case Seated s:
                switch (nudge) {
                    case LIFT: return new Parted();
                    default: return s;
                }
            case Parted p:
                switch (nudge) {
                    case LIFT: return new Raised();
                    default: return new Seated();
                }
            case Raised r:
                switch (nudge) {
                    case JAR: return new Seated();
                    default: return new Parted();
                }
        }
    }

    /** NEGATIVE CONTROL: returns Sash, takes no Sash, discriminates nothing. */
    public Sash state() {
        return state;
    }

    /** NEGATIVE CONTROL: the same, and its target resolves, which makes it worse. */
    public static Sash initial() {
        return new Seated();
    }

    /** NEGATIVE CONTROL for the codomain half: discriminates the state, folds to String. */
    public String describe() {
        return switch (state) {
            case Seated s -> "seated";
            case Parted p -> "parted";
            case Raised r -> "raised";
        };
    }
}
