package statefuldriver;

/**
 * F19 FIXTURE — the <b>stateful driver</b>: a transition function with the
 * hierarchy type on its way <em>out</em> and nothing of the hierarchy on its way
 * <em>in</em>.
 *
 * <p>Held fixed across this package: CENTRALIZED_DISPATCH, one discrimination of
 * the state per driver, the state read out of a field the driver owns, states
 * enumerated from {@code permits}. Varied: nothing but what the recognizer is
 * handed.
 *
 * <p>{@code StateMachineClassifier.findCentralizedTransitionMethods} required a
 * hierarchy-typed <em>parameter</em>, which admits {@code H transition(H current,
 * Event e)} and nothing else. A driver that holds its own state spells the same
 * function {@code H step(Event e)} and matched no recognizer at all — not
 * distributed (not declared on the hierarchy), not centralized (no H-typed
 * parameter). Whether such a machine was recovered came down to
 * {@code DispatchCommitDetector} incidentally finding a commit at the
 * discrimination, and that detector consults only the switch's <em>immediate</em>
 * syntactic context. So {@code return switch (state)} survived by luck, while the
 * machine below did not: its dispatch is a {@code switch} <b>statement</b> whose
 * arms return, so the commit is per-arm and there is nothing to see at the
 * switch's parent. The whole hierarchy was reported as "no transition producer
 * found".
 *
 * <p>That spelling is not exotic. It is how every per-event table was written
 * before pattern-matching switch, and {@code examples/hiddenreturns} already
 * carries it one level down as the shape the inter-procedural fold could not
 * summarise.
 *
 * <p>3 states, 6 transitions, 6/6, initial {@code Seated}.
 */
public sealed interface Sash permits Seated, Parted, Raised {
}
