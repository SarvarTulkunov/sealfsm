package emptyrelation;

/**
 * F27 FIXTURE (second half) — a machine whose relation is <b>entirely</b>
 * unrecovered, which used to print as a clean {@code 0/0}.
 *
 * <p>{@link ConnDriver} is accepted as a machine on its helpers' <em>signatures</em>:
 * {@code extractState1(Conn, Ev)} takes and returns the hierarchy, which is what
 * {@code findCentralizedTransitionMethods} asks. But nothing in the model
 * discriminates the state — the driver switches over the EVENT — so no arm has a
 * source state, and the helper bodies return from inside {@code synchronized},
 * which the walker does not descend, so no production is reached either. The walk
 * therefore yields <b>no transitions at all</b>.
 *
 * <p>{@code isDetectedEmpty()} used to require a non-empty transition list, on the
 * reasoning that a dispatched arm always yields an edge so "no transitions" is a
 * bug rather than a tier. The reasoning is right and the conclusion was backwards:
 * such a machine fell through all three tiers and was published as {@code 0/0},
 * which in a stratified recall table reads as a vacuous row rather than as the
 * total loss it is. An empty relation is the most complete failure of transition
 * recovery there is, so it is the last thing that may go unmarked.
 *
 * <p>3 states, exact, and {@code 0/0} <b>marked</b> as Tier 2 with the reason
 * given: no arm could be attributed, so not even an unresolved edge could be
 * recorded. {@code synchronized} is the standing probe here for the same reason it
 * is in {@code examples/opaquesuccessor} — teach the walker to descend it and this
 * fixture must fail loudly, at which point a new construct is chosen.
 */
public sealed interface Conn permits Down, Up, Failed { }
