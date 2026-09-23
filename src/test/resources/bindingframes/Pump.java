package bindingframes;

/** NEGATIVE B's hierarchy. */
public sealed interface Pump permits Pump.Idle, Pump.Primed, Pump.Running {
    record Idle() implements Pump {}
    record Primed() implements Pump {}
    record Running() implements Pump {}
}
