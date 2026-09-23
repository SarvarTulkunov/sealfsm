package bindingframes;

import bindingframes.Beacon.Blink;
import bindingframes.Beacon.Dark;
import bindingframes.Beacon.Steady;

/**
 * A state switch inside a folded callee discriminates WHATEVER ITS SELECTOR IS
 * BOUND TO — which is the current state only when the caller passed it.
 *
 * <p>{@code settle} switches on its parameter. F18 made such a switch
 * context-sensitive by assuming the switched value was the current state: arms for
 * other states were skipped as unreachable and the arm labels became the source.
 * That is right for {@code settle(current)} and wrong for {@code settle(new
 * Blink())} called from the {@code Dark} arm, where it skipped the {@code Blink}
 * arm that actually runs and published the {@code Dark} arm's result: {@code Dark
 * -> Dark}, fabricated, with the real {@code Dark -> Steady} dropped — behind a
 * clean 3/3.
 *
 * <ul>
 *   <li>{@code Dark}: the selector is bound to a construction, so the binding says
 *       exactly which arm runs — {@code Dark -> Steady}, unguarded.</li>
 *   <li>{@code Blink}: the CONTROL. The selector is bound to the current state,
 *       and F18's reading applies unchanged — {@code Blink -> Steady}.</li>
 *   <li>{@code Steady}: the selector is bound to a call with no body in the source
 *       set, so what it holds is unknown. Every arm may run, from {@code Steady},
 *       each under its own type test.</li>
 * </ul>
 */
public final class BeaconDriver {

    static BeaconTable TABLE;

    public static Beacon next(Beacon current, Cmd c) {
        return switch (current) {
            case Dark d -> settle(new Blink());
            case Blink b -> settle(current);
            case Steady s -> settle(TABLE.pick(c));
        };
    }

    private static Beacon settle(Beacon b) {
        return switch (b) {
            case Dark d -> new Dark();
            case Blink k -> new Steady();
            case Steady s -> new Blink();
        };
    }
}

/** No implementation in the source set. */
interface BeaconTable {
    Beacon pick(Cmd c);
}
