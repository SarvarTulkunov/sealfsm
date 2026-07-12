package examples.door;

/** Input alphabet, itself a sealed type (enumerable like the states). */
public sealed interface Event permits Push, Lock, Unlock {}
