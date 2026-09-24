package typedhandler;

/**
 * F35 DOCUMENTED COST: a real two-state machine with a single typed handler is
 * indistinguishable from {@link Shape}'s converter and is REJECTED with it. The
 * same price the instanceof-chain threshold pays ({@code chaindispatch}'s
 * {@code RelayBoard.reset()}): one discriminated state is too weak a signal.
 */
public sealed interface Lamp permits Lamp.Dark, Lamp.Lit {
    record Dark() implements Lamp {}
    record Lit() implements Lamp {}
}

final class LampSwitch {
    Lamp press(Lamp.Dark dark) { return new Lamp.Lit(); }
}
