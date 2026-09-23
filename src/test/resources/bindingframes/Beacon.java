package bindingframes;

/** The foreign-selector control's hierarchy. */
public sealed interface Beacon permits Beacon.Dark, Beacon.Blink, Beacon.Steady {
    record Dark() implements Beacon {}
    record Blink() implements Beacon {}
    record Steady() implements Beacon {}
}
