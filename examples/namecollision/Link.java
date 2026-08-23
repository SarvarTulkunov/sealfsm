package namecollision;

/**
 * NAME-COLLISION fixture — the case where two states share a simple name.
 *
 * <p>A state's id is what a transition endpoint, a DOT node and an SCXML
 * {@code id} all refer to, and the {@code permits} clause does not guarantee
 * that simple names are distinct. This hierarchy contains both legal shapes at
 * once:
 *
 * <ul>
 *   <li>{@link Idle} and {@link Legacy.Idle} — a top-level type beside a nested
 *       one. Legal even outside a named module, because the same-package rule for
 *       {@code permits} is satisfied: {@code Legacy} lives in this package. This
 *       is how the collision actually arises in real code, when a state class is
 *       lifted out of its holder and the old one is kept for compatibility.</li>
 *   <li>{@link Phase} and {@link Mode} — two permitted {@code enum}s that both
 *       declare a constant named {@code IDLE}. Since a permitted enum contributes
 *       its constants as child states, these are two distinct states spelled
 *       identically. This is the likelier of the two shapes: nothing about
 *       {@code IDLE}, {@code ERROR} or {@code NONE} discourages reuse.</li>
 * </ul>
 *
 * <p>Keyed on simple names, the two {@code Idle} states collapse into one node,
 * and — because {@code Transition} equality is
 * {@code (from, to, event, guard, resolved)} — the two {@code UPGRADE} edges
 * between them become the same tuple and one is discarded by the extractor's
 * transition set. That is a real transition lost with <em>no</em> unresolved
 * marker, the single outcome the record-everything invariant forbids; the
 * summary still reads a clean {@code n/n}. Graphviz hides the rest: node ids are
 * global, so a state declared inside two clusters silently becomes one node in
 * the first, without a warning.
 *
 * <p>The fix is {@code StateNaming}: an id is the shortest dot-separated suffix
 * of the qualified name that no other state shares, so uncollided states keep
 * their bare simple name and only the colliding ones lengthen —
 * {@code namecollision.Idle} / {@code Legacy.Idle} and {@code Phase.IDLE} /
 * {@code Mode.IDLE}.
 */
public sealed interface Link permits Idle, Legacy.Idle, Phase, Mode {
}
