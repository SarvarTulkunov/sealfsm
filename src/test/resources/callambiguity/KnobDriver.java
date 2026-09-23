package callambiguity;

import ext.Bar;
import ext.Foo;

import callambiguity.Knob.High;
import callambiguity.Knob.Low;

/**
 * DELIBERATELY DOES NOT COMPILE — {@code ext} is not in the source set, which is
 * the condition under test, so this lives in test resources and not in
 * {@code examples/} (the {@code unreadmember} fixtures are the precedent).
 *
 * <p>Under {@code noClasspath} an argument whose type did not resolve gives JDT
 * nothing to choose an overload with, and Spoon binds the FIRST same-arity
 * declaration (measured, Spoon 10.4.2). {@code turn(bar, new High())} is really a
 * call to {@code turn(Bar, Knob)}, which returns {@code Low}; Spoon binds it to
 * {@code turn(Foo, Knob)}, which forwards its argument — so folding the bound
 * body publishes {@code Low -> High} RESOLVED, a successor the program never
 * installs.
 *
 * <ul>
 *   <li>{@code Low}: an overload chosen by guess — UNRESOLVED, edge present.</li>
 *   <li>{@code High}: the CONTROL. {@code nudge} has no overload, so an unresolved
 *       argument type leaves nothing to choose between and the binding is exact —
 *       {@code High -> Low}, resolved.</li>
 * </ul>
 */
public final class KnobDriver {

    public static Knob next(Knob current, Foo foo, Bar bar) {
        return switch (current) {
            case Low l -> turn(bar, new High());
            case High h -> nudge(foo, new Low());
        };
    }

    static Knob turn(Foo f, Knob k) {
        return k;
    }

    static Knob turn(Bar b, Knob k) {
        return new Low();
    }

    static Knob nudge(Foo f, Knob k) {
        return k;
    }
}
