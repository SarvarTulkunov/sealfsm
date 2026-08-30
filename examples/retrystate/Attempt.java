package retrystate;

/**
 * F20 FIXTURE — the shape the finding is about: a state that REMEMBERS its
 * predecessor.
 *
 * <p>{@link Retrying} carries the {@code Attempt} it succeeded, which is the
 * ordinary way a retry or backoff protocol is written — the failure path has to
 * know what it is retrying, and RFC-style automata say so in the state itself
 * ({@code record Retrying(LcpState previous, int attempts)}). Every producer here
 * therefore writes {@code new Retrying(this, ...)} or {@code new Exhausted(this)}:
 * a hierarchy value in a constructor argument list.
 *
 * <p>The compositional veto read that as composition and rejected the ENTIRE
 * hierarchy — one expression, one whole machine lost, with the only diagnostic
 * saying "recursive data type". The two are not the same thing and the difference
 * is checkable: structural recursion <em>descends into</em> the value it matched
 * and rebuilds a node out of its PARTS ({@code new Neg(operand.simplify())}),
 * whereas this wraps the value WHOLE. A fold never does the latter — a node
 * containing itself is not a smaller problem — so an argument that <em>is</em> the
 * current state is not evidence of a tree at all.
 *
 * <p>Nothing else about this hierarchy is unusual: 4 states, one per-state
 * transition method, every successor a plain construction. It must extract
 * completely.
 *
 * <p>{@link Retrying} and {@link Exhausted} are classes rather than records for
 * the reason {@code chaindispatch.Pair} is: a {@code record Retrying(Attempt
 * previous, int attempts)} synthesises an {@code Attempt previous()} accessor,
 * which the distributed recognizer reads as a per-state transition method and the
 * resolver then reports as an unresolved edge out of {@code Retrying}. That is a
 * real, separate, documented behaviour of record components typed H; letting it
 * in here would bury what this fixture is for under noise it does not own.
 */
public sealed interface Attempt permits Ready, Retrying, Exhausted, Done {

    /** The per-state transition function. */
    Attempt on(Signal signal);
}
