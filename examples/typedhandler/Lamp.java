package typedhandler;

/**
 * F36 DEMONSTRATED STATE UPDATE (thesis Decision 4): a real two-state machine with
 * a single typed handler, recovered because its surrounding code shows the
 * successor becoming the current state ({@link LampPanel#press}).
 *
 * <p>Its handler has exactly the signature of {@link Shape}'s converter, so no
 * signature can separate them. Until F36 the tool tried one anyway: F35's "a
 * family must fix at least two source states" rejected both, and this machine
 * was the documented cost. The evidence the threshold stood in for is the
 * store-back, and it is present here and absent there.
 *
 * <p>Expected: 2 direct branches, 2 atomic states, 1/1 {@code Dark -> Lit},
 * commit evidence VIA_CALLER.
 */
public sealed interface Lamp permits Lamp.Dark, Lamp.Lit {
    record Dark() implements Lamp {}
    record Lit() implements Lamp {}
}

final class LampSwitch {
    Lamp press(Lamp.Dark dark) { return new Lamp.Lit(); }
}

/** The store-back. Unseeded, so the initial-state heuristics are handed nothing new. */
final class LampPanel {
    private final LampSwitch lampSwitch = new LampSwitch();
    private Lamp lamp;

    LampPanel(Lamp start) {
        this.lamp = start;
    }

    void press(Lamp.Dark dark) {
        lamp = lampSwitch.press(dark);
    }
}
