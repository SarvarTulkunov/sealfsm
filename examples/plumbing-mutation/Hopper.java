package plumbingmutation;

/**
 * A pure F2 (mutation-encoding) hierarchy: no method anywhere returns
 * {@code Hopper}, so the return-based paths find nothing and the mutation
 * fallback is the only recognizer that runs. As in {@code examples/gofcontext},
 * a mutation-only hierarchy has to opt in with the local {@link Fsm} marker.
 */
@Fsm
public sealed interface Hopper permits Empty, Filling, Full {
}
