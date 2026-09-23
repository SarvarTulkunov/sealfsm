package callambiguity;

/** The overload-guess control's hierarchy. */
public sealed interface Knob permits Knob.Low, Knob.High {
    record Low() implements Knob {}
    record High() implements Knob {}
}
