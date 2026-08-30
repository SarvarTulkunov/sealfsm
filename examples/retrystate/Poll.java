package retrystate;

/**
 * F20, CENTRALIZED spelling — the same predecessor-carrying state, dispatched by
 * one switch over the hierarchy instead of by per-state methods.
 *
 * <p>It is here because the veto is enforced in three places on three different
 * recognition paths, and the finding is about all three. {@link PollDriver}'s arms
 * hand the successor the arm's own TYPE-PATTERN BINDING ({@code case Waiting w ->
 * new Waiting(w, ...)}) rather than {@code this}. That is the same value under a
 * different spelling — the state the dispatch just matched — so it must be read
 * the same way. Judged syntactically it is a variable read like any other, and
 * {@code DispatchCommitDetector} discarded the whole producer over it, which costs
 * the hierarchy its only transition function.
 *
 * <p>{@code new Waiting(w, w.misses() + 1)} is the second half, and it fails
 * differently: {@code w.misses()} returns an {@code int}, but a flat scan of the
 * argument's subtree sees the RECEIVER {@code w} and calls the argument a
 * hierarchy value. A counter computed from the current state is the most ordinary
 * thing a retry state does, and it read as composition.
 */
public sealed interface Poll permits Fresh, Waiting, Stale {
}
