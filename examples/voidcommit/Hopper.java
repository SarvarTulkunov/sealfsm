package voidcommit;

/**
 * TIER 2 FIXTURE — the same property as {@code examples/opaquesuccessor}, at the
 * cell that needed a new capability: the commit lives inside a {@code void}
 * callee.
 *
 * <p>{@code HopperDriver.step} dispatches exhaustively over the state, and every
 * arm is a bare call statement. Java <em>discards</em> an expression statement's
 * value (JLS §14.8), so nothing at the dispatch can prove or disprove a commit —
 * there is no {@code return} to read a codomain from, no assignment, no local
 * declaration. Before the k = 1 commit-existence probe this hierarchy was not a
 * recognised dispatch at all, and because the analyzer only enumerated states on
 * the accepted path, the whole hierarchy was lost: <b>zero states</b> for a
 * {@code permits} clause that names four.
 *
 * <p>Must produce: one machine; 4 states, exactly the {@code permits} clause;
 * {@code CommitEvidence.VIA_CALLEE} — and only here, so the probe cannot quietly
 * claim credit for machines the direct rules already find;
 * {@code resolvedTransitionCount() == 0}; one unresolved edge per dispatched arm,
 * each with a known source state.
 *
 * <p>The successor is unrecoverable on purpose, and by two independent means so
 * that fixing either alone does not silently turn this into a Tier 1 fixture: the
 * commit's right-hand side is an arithmetic index into an array field, and the
 * probe deliberately never asks which state is installed. Commit existence and
 * successor identity are separate questions with separate limits, and this
 * fixture is the demonstration that the first can be answered while the second is
 * not.
 *
 * <p>Held as close as possible to {@code examples/voidfold}: same hierarchy
 * shape, same driver shape, same method names, same arity, same argument. The
 * only difference between the two is the declared type of the field the callee
 * writes — see that fixture's Javadoc.
 */
public sealed interface Hopper permits Empty, Filling, Full, Jammed { }
