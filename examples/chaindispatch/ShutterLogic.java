package chaindispatch;

/**
 * Hierarchy-in / hierarchy-out, dispatched by an {@code instanceof} chain and
 * committed by {@code return}. Three things about a chain that a {@code switch}
 * never has to answer are all exercised here, and each of them can be wrong in a
 * way that looks clean in the output:
 *
 * <ol>
 *   <li><b>A conjoined event test.</b> {@code current instanceof Open && command
 *       == LOWER} is one condition holding two different things: the type test
 *       selects the from-state and must NOT survive as a guard, while the rest is
 *       an ordinary condition that still splits into a Σ label. Keeping the whole
 *       condition as a guard both loses the source state and labels the edge with
 *       its own arm.</li>
 *   <li><b>A residual under negation.</b> Because the {@code Open} link carries an
 *       extra condition, {@code Open} is <em>still reachable</em> below it — the
 *       branch is skipped whenever the command is not {@code LOWER}. Removing
 *       {@code Open} from the residual deletes the {@code Open --> Open} edge;
 *       keeping it without the negation reports that edge as unconditional, which
 *       is a claim the source does not make. {@code Closing}, tested with nothing
 *       else, really is gone from the residual.</li>
 *   <li><b>A trailing statement after a closed chain.</b> Every link returns, so
 *       {@code return current;} is reached exactly in the states no link claimed.
 *       That is a self-loop on each of them — {@code Open} under the negation and
 *       {@code Shut} unconditionally — and not the {@code <unknown>} origin a
 *       single from-state is forced to report. The closed-world reasoning is the
 *       {@code permits} clause's: the selector is one of the permitted subtypes.</li>
 * </ol>
 *
 * <p>5 transitions, 5 resolved, and the relation is exactly the one a reader
 * traces by hand — which is the point of putting the two spellings of one
 * automaton in the corpus at all.
 */
public final class ShutterLogic {

    public static Shutter next(Shutter current, Command command) {
        if (current instanceof Open && command == Command.LOWER) {
            return new Closing();
        } else if (current instanceof Closing) {
            if (command == Command.SEAL) {
                return new Shut();
            }
            return current;
        }
        return current;
    }
}
