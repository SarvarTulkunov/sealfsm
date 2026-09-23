package bindingframes;

/** NEGATIVE A's and NEGATIVE C's hierarchy. */
public sealed interface Lamp permits Lamp.Off, Lamp.On, Lamp.Dim {
    record Off() implements Lamp {}
    record On() implements Lamp {}
    record Dim() implements Lamp {}
}
