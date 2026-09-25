package typedhandler;

/**
 * F36 CONVERTER-USE CONTROL (thesis Decision 4): the same shape as {@link Length},
 * two converters from two states, with callers, and every caller uses the result
 * as DATA. The value is handed to {@link Display#label}, which switches over it to
 * build a string. Nothing stores it back as a current state.
 *
 * <p>That is an ESTABLISHED conversion within the source set, and Decision 4
 * rejects it outright: no machine, no candidate, and no member of it published as
 * a state on either channel. {@code Display.label} is itself a discrimination of
 * the hierarchy (an exhaustive fold into {@code String}). Before F36 that alone
 * made this hierarchy a candidate, and the converter verdict is what withholds it.
 */
public sealed interface Temperature permits Temperature.Celsius, Temperature.Fahrenheit {
    record Celsius(double deg) implements Temperature {}
    record Fahrenheit(double deg) implements Temperature {}
}

final class Thermometer {
    static Temperature toFahrenheit(Temperature.Celsius c) {
        return new Temperature.Fahrenheit(c.deg() * 9 / 5 + 32);
    }

    static Temperature toCelsius(Temperature.Fahrenheit f) {
        return new Temperature.Celsius((f.deg() - 32) * 5 / 9);
    }
}

final class Display {
    String show(Temperature.Celsius c) {
        return label(Thermometer.toFahrenheit(c));
    }

    String showBack(Temperature.Fahrenheit f) {
        return label(Thermometer.toCelsius(f));
    }

    private static String label(Temperature t) {
        return switch (t) {
            case Temperature.Celsius c -> c.deg() + " C";
            case Temperature.Fahrenheit f -> f.deg() + " F";
        };
    }
}
