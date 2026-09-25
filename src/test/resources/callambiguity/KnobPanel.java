package callambiguity;

import ext.Bar;
import ext.Foo;

/**
 * F36 store-back (thesis Decision 4): installs {@link KnobDriver#next}'s successor
 * as the current state, so the hierarchy is a machine whose relation this
 * directory's overload-guess test can inspect. Unseeded. Like the rest of the
 * directory, it deliberately does not compile: {@code ext} is absent.
 */
final class KnobPanel {
    private Knob knob;

    void turn(Foo foo, Bar bar) {
        knob = KnobDriver.next(knob, foo, bar);
    }
}
