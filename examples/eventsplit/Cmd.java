package eventsplit;

/** A closed event alphabet: Σ = {Arm, Fire, Reset}, exact by the permits clause. */
public sealed interface Cmd permits Arm, Fire, Reset {}

final class Arm implements Cmd {}

final class Fire implements Cmd {}

final class Reset implements Cmd {}
