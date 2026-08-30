package statefuldriver;

/**
 * The second failure the same blind spot causes, and the one that is a
 * <em>soundness</em> problem rather than a recall one: fabricated edges.
 *
 * <p>This driver's dispatch IS a {@code return switch (state)}, so
 * {@code DispatchCommitDetector} sees it and the machine was never lost. It was
 * reported wrong. The extractor excludes an inter-procedural helper from being
 * walked a second time on its own — that is F3 — by collecting the callees of
 * every recognised transition function. The driver was not one, so its callees
 * were never recognised as its helpers, and each per-state helper was extracted
 * standalone with no from-state: three edges out of {@code <entry>} to {@code ?},
 * on top of the nine real ones the fold had already recovered correctly.
 *
 * <p>The damage is in the denominator. The relation was complete and every target
 * resolved, yet the machine reported <b>9/12</b> — three unresolved transitions
 * that do not exist in the program, against a recall figure the thesis publishes.
 * A reader auditing that number finds three edges from a source state that is not
 * a state.
 *
 * <p>The helpers take {@code Pump} and return {@code Pump}, which is what puts
 * them in the recognised set in the first place; they are the ordinary way a large
 * centralized switch is factored, and the shape all of {@code lcp_automation},
 * {@code hiddenreturns} and {@code dhcp-client-claude} are built from. The only
 * difference here is that their caller holds the state instead of being handed it.
 *
 * <p>3 states, 9 transitions, 9/9, initial {@code Idle}.
 */
public sealed interface Pump permits Idle, Priming, Running {
}
