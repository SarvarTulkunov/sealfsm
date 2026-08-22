package plumbing;

/** A three-state conveyor; the transition function lives in {@link ConveyorMachine}. */
public sealed interface Conveyor permits Stopped, Running, Jammed {
}
