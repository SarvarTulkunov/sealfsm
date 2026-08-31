package carrierdispatch;

/**
 * Three centralized switches over the same hierarchy, differing ONLY in what
 * they fold into. Held on one class deliberately: no difference of file or
 * enclosing context can stand in for the codomain, so the codomain is the only
 * thing the recognizer can be reacting to.
 *
 * <p>Before the dispatch and commit axes were separated, all three were rejected
 * alike. The carrier recognizer required a per-subtype override and the switch
 * recognizer required the commit to be a hierarchy value, so a centralized
 * transition table returning a wrapper satisfied both halves of a valid pair and
 * matched neither recognizer.
 */
public final class Router {

    /** THE CASE: CENTRALIZED_SWITCH x CARRIER_RETURN. */
    public static Step route(Signal current, int event) {
        return switch (current) {
            case Idle i -> new Step(new Live(), "start");
            case Live l -> new Step(new Done(), "finish");
            case Done d -> new Step(new Done(), "stay");
        };
    }

    /** NEGATIVE CONTROL: the identical discrimination, folded into a type with
     *  no hierarchy-typed component. An exhaustive fold; must stay rejected. */
    public static Note describe(Signal current) {
        return switch (current) {
            case Idle i -> new Note("idle", 0);
            case Live l -> new Note("live", 1);
            case Done d -> new Note("done", 2);
        };
    }

    /** AMBIGUITY CONTROL: two hierarchy-typed slots, so no slot is THE
     *  successor. Must be declined rather than resolved by field order. */
    public static Pair fork(Signal current) {
        return switch (current) {
            case Idle i -> new Pair(new Live(), new Done());
            case Live l -> new Pair(new Done(), new Idle());
            case Done d -> new Pair(new Done(), new Done());
        };
    }
}
