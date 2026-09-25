package enumbodies;

/**
 * F36 driver (thesis Decision 4): installs each machine's successor as its
 * current state. A returned hierarchy value is evidence that a value is
 * PRODUCED. Only its store-back separates a transition from a conversion, so
 * without this class every machine in the package is a provisional candidate.
 *
 * <p>Deliberately unseeded: the fields are set through the constructor, so the
 * initial-state heuristics are not handed a seed the fixture never had, and
 * every other number in the package stays what it was.
 */
final class Panel {
    private Dial dial;
    private Knob knob;
    private Lamp lamp;
    private Valve valve;

    Panel(Dial dial, Knob knob, Lamp lamp, Valve valve) {
        this.dial = dial;
        this.knob = knob;
        this.lamp = lamp;
        this.valve = valve;
    }

    void turnDial() {
        dial = DialDriver.next(dial);
    }

    void turnKnob() {
        knob = knob.next();
    }

    void pressLamp() {
        lamp = lamp.press();
    }

    void toggleValve() {
        valve = valve.toggle();
    }
}
