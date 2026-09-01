package foldbinding;

import foldbinding.Ballast.List_;
import foldbinding.Ballast.Trim;

/**
 * The control for what rule (5) may NOT do on its way back out.
 *
 * <p>Resolving a callee's parameter means resolving the expression the caller
 * passed, in the caller's own body. Rule 4 lives there too, and it licenses "a
 * root-typed parameter read means stay in the matched state" for <em>any</em>
 * parameter — which is a standing over-approximation at a top-level dispatch
 * (see CLAUDE.md's next-steps list). The fold must not route into it: {@code
 * spare} is a second hierarchy-typed parameter that is NOT the discriminated
 * value, and it reached this arm only because the callee's own parameter was
 * already shown not to be the current state.
 *
 * <p>So {@code fwd(spare)} must stay UNRESOLVED. Publishing {@code Trim -->
 * Trim} would be argument position and parameter-ness standing in for a proof,
 * and it is invisible in the score — the {@code List_} arm resolves either way,
 * so the machine reads a clean 2/2 with one edge fabricated.
 */
public final class BallastDriver {

    public static Ballast next(Ballast current, Ballast spare, Sig event) {
        return switch (current) {
            case Trim t -> fwd(spare);
            case List_ l -> fwd(new Trim());
        };
    }

    private static Ballast fwd(Ballast s) {
        return s;
    }
}
