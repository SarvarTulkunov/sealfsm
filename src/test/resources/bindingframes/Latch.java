package bindingframes;

/** NEGATIVE D's hierarchy. */
public sealed interface Latch permits Latch.Open, Latch.Shut, Latch.Stuck {
    record Open() implements Latch {}
    record Shut() implements Latch {}
    record Stuck() implements Latch {}
}
