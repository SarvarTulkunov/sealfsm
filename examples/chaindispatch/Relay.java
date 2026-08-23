package chaindispatch;

/**
 * F17 FIXTURE — {@code instanceof}-chain dispatch in a NAMED method.
 *
 * <p>Held fixed across this package: CENTRALIZED_DISPATCH, one discrimination of
 * the state per driver, states enumerated from {@code permits}. Varied: nothing
 * but what the chain rule is handed.
 *
 * <p>The package is deliberately NOT named after the keyword: guard text is
 * compared as SOURCE text, and a package called {@code instanceofchain} makes
 * every qualified name in every guard contain the substring {@code instanceof} —
 * which quietly defeats any assertion that no type test survived as a guard. The
 * fixture found that on itself.
 *
 * <p>{@code Relay} is the shape the finding is about — a field selector, no
 * hierarchy-typed parameter, and no {@code switch} anywhere. Before F17 it
 * matched no recognizer at all: {@code DispatchCommitDetector} scanned for a
 * switch, {@code findCentralizedTransitionMethods} for a hierarchy-typed
 * parameter, and {@code findDistributedTransitionMethods} for a method on the
 * hierarchy. This is the dominant dispatch shape in Java written before pattern
 * matching, which is most Java, so its absence was a recall hole rather than a
 * scope line.
 */
public sealed interface Relay permits Idle, Live, Tripped {
}
