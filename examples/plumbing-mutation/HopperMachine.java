package plumbingmutation;

import java.util.Objects;

/**
 * F10 NEGATIVE CONTROL — the expression statement that IS the commit.
 *
 * <p>{@code examples/plumbing} pins that an expression statement no commit form
 * claims contributes no transition. Taken one step too far that rule reads
 * "ignore expression statements", which would delete the entire F2 mutation
 * encoding from the tool: {@code ctx.setState(new Filling())} is an expression
 * statement too. The difference is not the syntax, it is whether a recognised
 * commit form claims it — so the mutator branch must still fire here, beside the
 * same plumbing the sibling fixture rejects.
 *
 * <p>It lives in its own hierarchy because the F2 path runs only as a fallback,
 * for a hierarchy where nothing returns the hierarchy type. Put beside a
 * VALUE_RETURN machine it would never execute, and the control would silently
 * assert nothing.
 *
 * <p>Second half of F10: {@link #drive} commits from the arrow arms of a switch
 * STATEMENT. Spoon wraps such an arm in a synthetic {@code CtYieldStatement} even
 * though {@code yield} is illegal outside a switch expression, so the arm used to
 * reach {@code handleValue} as a produced value — where a void {@code setState}
 * call resolves to nothing and became an unresolved edge. Unwrapping the synthetic
 * yield routes it back through {@code walk}, where the mutator branch claims it.
 * {@link #pump} commits the same three edges from a colon-arm switch, so the two
 * spellings must report the same relation.
 */
public final class HopperMachine {

    private int commitCount;

    /** Arrow-arm switch STATEMENT: each arm arrives wrapped in a synthetic yield. */
    public void drive(Hopper current, HopperContext ctx) {
        // The same plumbing examples/plumbing rejects — here it must still be
        // rejected while the setState calls below are still claimed.
        Objects.requireNonNull(current, "current must not be null");
        audit(current);
        switch (current) {
            case Empty e -> ctx.setState(new Filling());
            case Filling f -> ctx.setState(new Full());
            case Full u -> ctx.setState(new Empty());
        }
        this.commitCount++;
        log("committed " + this.commitCount);
    }

    /**
     * Colon-arm switch: the commit form the arrow arms must agree with. Its
     * relation is deliberately DISJOINT from {@link #drive}'s, so the two arm
     * spellings stay separately attributable — were both to encode the same
     * edges, the set-valued transition store would dedup them and the fixture
     * could not tell which spelling actually produced anything.
     */
    public void pump(Hopper current, HopperContext ctx) {
        switch (current) {
            case Empty e:
                ctx.setState(new Full());
                break;
            case Filling f:
                ctx.setState(new Empty());
                break;
            case Full u:
                ctx.setState(new Filling());
                break;
        }
    }

    private void audit(Hopper current) {
        log("in " + current);
    }

    private void log(String message) {
        if (message.isEmpty()) {
            throw new IllegalArgumentException("empty");
        }
    }
}
