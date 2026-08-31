package carrierdispatch;

/** The carrier for the chain-dispatched hierarchy. */
public record Hop(Latch next, String action) {
}
