package examples.localvar;

/**
 * Sealed root for the reassigned-local micro-benchmark (finding F1).
 *
 * <p>The transition function in {@link GateMachine} computes the next state
 * through a <em>reassigned local</em> rather than a direct {@code return}. A
 * naive resolver reads that local's declared type — the sealed root {@code Gate}
 * — and emits a false, guardless self-loop while discarding the real target; the
 * reaching-definitions pass must instead recover both the guarded edge and the
 * else-branch self-loop.
 */
public sealed interface Gate permits Open, Closed {}
