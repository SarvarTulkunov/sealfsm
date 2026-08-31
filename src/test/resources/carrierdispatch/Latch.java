package carrierdispatch;

/** A second hierarchy, dispatched by an instanceof CHAIN rather than a switch. */
public sealed interface Latch permits Open, Shut {
}
