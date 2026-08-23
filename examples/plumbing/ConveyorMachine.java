package plumbing;

import java.util.Objects;

/**
 * F10 fixture — the tool must see past ordinary plumbing.
 *
 * <p>This is the same machine twice, in the two commit forms, each buried in the
 * bookkeeping that real production code actually contains: a null-check prelude,
 * a log line, a metrics counter, an audit call. None of those installs a
 * successor, and none of them may appear as a transition.
 *
 * <p>The defect this pins: {@code walk} ended in a catch-all that handed ANY
 * expression reaching it to the successor resolver. Every call site passes a
 * <em>statement</em>, so the only thing that catch-all ever saw was an expression
 * statement — whose value Java discards (JLS §14.8). A discarded value cannot be
 * a committed successor, which makes the rule exact rather than heuristic. Before
 * F10 each of the statements below became an edge: the hierarchy-typed ones
 * ({@link #identity}, {@code requireNonNull}) as targets, the rest as unresolved
 * gaps sourced at {@code <unknown>}. Found on {@code examples/lcp_automation},
 * whose {@code Objects.requireNonNull(event, ...)} prelude inflated a 105-edge
 * automaton to 115.
 *
 * <p>The NEGATIVE CONTROL lives in {@code examples/plumbing-mutation}, because
 * the F2 mutation path runs only as a fallback for a hierarchy with no
 * return-based transition method and so cannot be exercised from here. It carries
 * the other half of the rule: {@code ctx.setState(...)} is ALSO an expression
 * statement, and it IS the commit. "Ignore expression statements" taken literally
 * would delete the entire mutation encoding, so the rule must be "ignore
 * expression statements that no commit form claims".
 */
public final class ConveyorMachine {

    private Conveyor state = new Stopped();
    private int transitionCount;

    /** VALUE_RETURN, wrapped in a null-check prelude and a log line. */
    public Conveyor next(Conveyor current, Command command) {
        // Plumbing #1: returns its argument, so it is HIERARCHY-TYPED. A guard
        // that merely required a hierarchy type would still record this one.
        Objects.requireNonNull(current, "current must not be null");
        // Plumbing #2: hierarchy-typed and defined IN THE MODEL, so the F3
        // inter-procedural summariser can read its body and WILL resolve it to a
        // self-loop. This is the sharpest case: nothing about the expression
        // itself is unresolvable, so only its statement position rules it out.
        identity(current);
        // Plumbing #3: void, and its argument is hierarchy-typed.
        audit(current, command);
        // Plumbing #4: void with no interesting argument at all.
        log("dispatching " + command);

        return switch (current) {
            case Stopped s -> command == Command.START ? new Running() : s;
            case Running r -> command == Command.JAM ? new Jammed() : r;
            case Jammed j -> command == Command.CLEAR ? new Stopped() : j;
        };
    }

    /**
     * FIELD_MUTATION, with the counter bump — the classic statement sitting right
     * beside the commit — between the dispatch and the return.
     */
    public Conveyor step(Command command) {
        Objects.requireNonNull(command, "command must not be null");
        this.state = switch (this.state) {
            case Stopped s -> command == Command.START ? new Running() : s;
            case Running r -> command == Command.JAM ? new Jammed() : r;
            case Jammed j -> command == Command.CLEAR ? new Stopped() : j;
        };
        this.transitionCount++;
        log("now " + this.state);
        return this.state;
    }

    /** In-model, hierarchy-typed, and trivially summarisable — yet not a commit. */
    private Conveyor identity(Conveyor current) {
        return current;
    }

    private void audit(Conveyor current, Command command) {
        log(command + " in " + current);
    }

    private void log(String message) {
        if (message.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
    }
}
