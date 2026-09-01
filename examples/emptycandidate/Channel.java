package emptycandidate;

/**
 * TIER 3 FIXTURE WITH COMPOSITE STATES — the candidate channel must report the
 * <b>same complete state set the machine path would have</b>, not a flattened
 * approximation of it.
 *
 * <p>The {@code permits} clause names one plain state, one <b>nested sealed</b>
 * type and one <b>permitted enum</b>, so enumerating it exercises both recursive
 * composite expansion and enum-constant expansion — the two places where "the
 * state set" is more than the permits clause read literally. A candidate reported
 * as three states would be reporting the permits clause; the correct answer is
 * seven, because {@code Active} contributes its two members and {@code Draining}
 * contributes its two constants, exactly as they would if this hierarchy were
 * accepted.
 *
 * <p>The commit is unproven the same way {@code examples/voidfold}'s is — the
 * dispatch is exhaustive and its arms fold into a {@code String} — so this is a
 * Tier 3 candidate and <b>must produce no machine</b>. The fixture is not a second
 * copy of that control: what it pins is the state set on the candidate channel,
 * which {@code voidfold}'s four flat leaf states cannot exercise.
 *
 * <p>{@code Active} is deliberately <em>not</em> re-offered as a machine of its
 * own: nothing in the model dispatches on {@code Active} specifically, so the
 * re-offer worklist finds no producer for it either and it is correctly reported
 * as a plain rejection. That keeps this fixture's assertion about ONE candidate
 * rather than about the interaction between two features.
 */
public sealed interface Channel permits Idle, Active, Draining { }
