package bindingframes;

/** The recursion-identity control's hierarchy. */
public sealed interface Tide permits Tide.Ebb, Tide.Flood {
    record Ebb() implements Tide {}
    record Flood() implements Tide {}
}
