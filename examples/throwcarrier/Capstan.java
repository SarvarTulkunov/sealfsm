package throwcarrier;

/**
 * F21 FIXTURE — a POLYMORPHIC hierarchy committing through a POLY_CARRIER, whose
 * arms vary only in <em>what kind of call</em> produces the carrier.
 *
 * <p>The encoding is held fixed and is the one {@code examples/tcp} already
 * establishes: every permitted subtype overrides {@link #on(Order)}, whose result
 * is a {@link Haul} wrapping the successor. What varies across the six arms below
 * is whether the called method can return at all.
 *
 * <p>{@code Undefined.illegal(this, o)} and {@code Haul.stay(this)} are the same
 * expression shape — a static call whose own type is outside the hierarchy,
 * carrying a hierarchy-typed first argument — and the carrier walk's one-level
 * unwrap reads the argument of either as the successor. Nothing in the expression
 * separates them; only the callee's body does. {@link Taut#on(Order)} puts both in
 * one method so no difference of file or context can stand in for that.
 */
public sealed interface Capstan permits Slack, Taut, Snagged {

    /** Compute the haul resulting from {@code order} in this state. */
    Haul on(Order order);
}
