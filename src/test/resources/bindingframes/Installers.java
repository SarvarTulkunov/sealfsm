package bindingframes;

/**
 * F36 store-backs (thesis Decision 4) for this directory's value-returning
 * machines.
 *
 * <p>Each hierarchy here pins how a successor's IDENTITY is bound across a call
 * (F25, F29), and none was written with a caller. A returned hierarchy value is a
 * transition only once a caller installs it as the current state; without one it
 * is indistinguishable from a conversion. These methods supply that evidence and
 * nothing else. Each calls its machine's entry point exactly as a driver would and
 * stores the result back. The fields are unseeded, so no initial state is handed
 * to the heuristics. {@code Bay} needs nothing: it commits inside its own
 * callee.
 */
final class Installers {
    private Beacon beacon;
    private Gear gear;
    private Lamp lamp;
    private Latch latch;
    private Pump pump;
    private Tide tide;
    private Valve valve;
    private Winch winch;

    void beacon(Cmd c) {
        beacon = BeaconDriver.next(beacon, c);
    }

    void gear(Cmd c) {
        gear = gear.shift(c);
    }

    void lamp(Cmd c) {
        lamp = LampDriver.next(lamp, c);
    }

    void latch(Cmd c) {
        latch = LatchDriver.next(latch, c);
    }

    void pump(Router router, PlainRouter plain, Cmd c) {
        pump = PumpDriver.next(pump, router, plain, c);
    }

    void tide(Cmd c) {
        tide = TideDriver.next(tide, c);
    }

    void valve(Cmd c) {
        valve = ValveDriver.next(valve, c).next();
    }

    void winch(Gate gate, StrictGate strict) {
        winch = WinchDriver.step(winch, gate, strict).next();
    }
}
