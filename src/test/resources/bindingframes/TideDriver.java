package bindingframes;

import bindingframes.Tide.Ebb;
import bindingframes.Tide.Flood;

/**
 * RECURSION is a property of the call chain's DECLARATIONS, not of a signature
 * string. {@code TideDriver.step(Tide)} delegates to {@code TideHelper.step(Tide)}:
 * two different methods whose signature strings are identical, because a
 * signature carries no declaring type. Keyed on the string, the second hop read as
 * the first calling itself and was declined, so both arms were unresolved. Keyed
 * on identity, nothing here recurses and both resolve.
 */
public final class TideDriver {

    public static Tide next(Tide current, Cmd c) {
        return switch (current) {
            case Ebb e -> step(new Flood());
            case Flood f -> step(new Ebb());
        };
    }

    static Tide step(Tide t) {
        return TideHelper.step(t);
    }
}

/** Same simple name and parameter list as {@link TideDriver#step}; a different method. */
final class TideHelper {
    private TideHelper() {}

    static Tide step(Tide t) {
        return t;
    }
}
