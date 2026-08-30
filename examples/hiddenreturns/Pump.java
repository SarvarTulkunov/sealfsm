package hiddenreturns;

/** A four-state pump; the transition function lives in {@link PumpController}. */
public sealed interface Pump permits Idle, Priming, Running, Halted {
}
