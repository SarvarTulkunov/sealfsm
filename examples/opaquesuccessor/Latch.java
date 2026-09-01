package opaquesuccessor;

/**
 * TIER 2 FIXTURE — <b>the state set is exact even when the transition relation
 * is empty</b>, at the {@code CENTRALIZED_SWITCH} locus.
 *
 * <p>This fixture exists to make one property falsifiable: that state
 * enumeration is complete <em>regardless of how badly transition extraction
 * does</em>. A corpus in which every fixture's transitions succeed cannot
 * distinguish "states are exact" from "states are exact when transitions
 * resolve", which is a materially weaker claim and the one the tool used to
 * make by accident.
 *
 * <p>The dispatch is present, exhaustive and commits by codomain — the host
 * returns {@code Latch}, so {@code CommitClassifier} proves the commit
 * {@code DIRECT}ly, with no probe involved. Every arm then hands the successor
 * to {@link LatchMachine#pick}, whose {@code return} sits inside a
 * {@code synchronized} block: a construct the walker does not descend, so the
 * inter-procedural fold reaches no return and the successor is unrecoverable.
 *
 * <p>Must produce: one machine; 4 states, exactly the {@code permits} clause;
 * {@code CommitEvidence.DIRECT}; {@code resolvedTransitionCount() == 0}; and one
 * unresolved transition <b>per dispatched arm</b>, each with a known source
 * state. An empty transition list here would be a silent drop and a violation of
 * the record-everything invariant — the output must show {@code Idle --?--> ?},
 * not nothing.
 *
 * <p><b>The standing probe.</b> {@code synchronized} is not special. It is
 * merely what is currently outside the walker's reach, and the fixture depends
 * on that only for its <em>zero</em>. Teach the walker to descend
 * {@code synchronized} and this fixture must fail LOUDLY — its edges would
 * resolve — at which point a new construct outside the walk is chosen and the
 * assertion is re-pointed. That is the correct outcome: the test asks what the
 * walk actually reached, not whether the body matched a list of constructs
 * someone remembered to extend. {@code examples/hiddenreturns} and
 * {@code examples/nonreturning} carry the same probe one level down.
 */
public sealed interface Latch permits Idle, Armed, Fired, Spent { }
