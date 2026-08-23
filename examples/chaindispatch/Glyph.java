package chaindispatch;

/**
 * NEGATIVE CONTROL for the commit requirement — the {@code instanceof} spelling
 * of {@code examples/foreignfold}. {@link GlyphNamer} discriminates every
 * permitted subtype of {@code Glyph} and assigns the result to a field, and must
 * still be REJECTED, because the field it assigns is a {@code String}.
 *
 * <p>This is the whole precision guard, not a check bolted on afterwards: an
 * exhaustive fold and a transition chain are structurally indistinguishable at
 * the type test, and only the codomain separates them. Accepting it would report
 * an automaton whose every state has zero transitions.
 *
 * <p>The control is load-bearing only because it is invisible to everything else:
 * {@code name()} takes no hierarchy-typed parameter and returns {@code void}, so
 * no other recognizer would have claimed it, and the rejection can be attributed
 * to the commit test alone.
 */
public sealed interface Glyph permits Dot, Dash {
}
