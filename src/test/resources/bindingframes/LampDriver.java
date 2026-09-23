package bindingframes;

import bindingframes.Lamp.Dim;
import bindingframes.Lamp.Off;
import bindingframes.Lamp.On;

/**
 * NEGATIVE C — a chain longer than the inter-procedural budget — beside its own
 * control, and NEGATIVE A — a parameter reassigned before it is committed.
 *
 * <ul>
 *   <li>{@code Off}: {@code first -> second -> third}, three hops. At the
 *       committed budget of 2 the third is not entered: UNRESOLVED, edge present.
 *       At a budget of 3 it resolves, which is what the depth sweep shows.</li>
 *   <li>{@code On}: {@code second -> third}, two hops, the same forwarders — the
 *       CONTROL that the budget, not the forwarders, is what stops {@code Off}.</li>
 *   <li>{@code Dim}: {@code overwrite} may replace its parameter before returning
 *       it, so at the return it no longer holds the argument. The binding is
 *       refused and the edge is recorded UNRESOLVED.</li>
 * </ul>
 */
public final class LampDriver {

    static boolean eco;

    public static Lamp next(Lamp current, Cmd c) {
        return switch (current) {
            case Off o -> first(new On());
            case On o -> second(new Dim());
            case Dim d -> overwrite(new Off());
        };
    }

    private static Lamp first(Lamp x) {
        return second(x);
    }

    private static Lamp second(Lamp x) {
        return third(x);
    }

    private static Lamp third(Lamp x) {
        return x;
    }

    private static Lamp overwrite(Lamp x) {
        if (eco) x = new Dim();
        return x;
    }
}
