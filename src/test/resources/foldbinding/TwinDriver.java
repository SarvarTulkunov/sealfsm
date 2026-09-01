package foldbinding;

import foldbinding.Twin.Dead;
import foldbinding.Twin.Live;

/**
 * The AMBIGUITY control for F25, on the ARGUMENT side of a call rather than the
 * component side of a type — the existing {@code carrierdispatch} control covers
 * the type side only.
 *
 * <p>Both dispatches call a forwarder with TWO hierarchy-typed arguments, so the
 * argument list alone cannot say which is the successor. They differ in one
 * thing: how many hierarchy-typed slots the forwarder's construction has. Held
 * on one class so nothing but that can be what the analysis reacts to.
 *
 * <ul>
 *   <li>{@code hop} — the POSITIVE. {@code one(a, b)} builds a one-slot
 *       {@code Solo} out of {@code a}; {@code b} is passed and never placed. The
 *       binding is by USE, not by "any hierarchy-typed argument is a target", so
 *       exactly one successor is claimed.</li>
 *   <li>{@code step} — the CONTROL. {@code pack(a, b)} builds a two-slot
 *       {@code Duo} out of both. Nothing in the program says which slot succeeds
 *       the current state, so neither may be published as a resolved successor.
 *       Choosing by declaration or argument order would be a guess, and here it
 *       would show up as a self-loop that the source does not contain.</li>
 * </ul>
 */
public final class TwinDriver {

    public static Solo hop(Twin current, Sig event) {
        return switch (current) {
            case Live l -> one(new Dead(), new Live());
            case Dead d -> one(new Live(), new Dead());
        };
    }

    public static Duo step(Twin current, Sig event) {
        return switch (current) {
            case Live l -> pack(new Dead(), new Live());
            case Dead d -> pack(new Live(), new Dead());
        };
    }

    /** One slot: `a` is placed, `b` is discarded. */
    private static Solo one(Twin a, Twin b) {
        return new Solo(a);
    }

    /** Two slots: both are placed, and nothing says which one succeeds. */
    private static Duo pack(Twin a, Twin b) {
        return new Duo(a, b);
    }
}
