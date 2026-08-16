package examples.barefield;

/**
 * Fixture for the bare-field commit idiom (Part A, A2c). Structurally identical
 * to {@code examples.accumulator} and to {@code http2stream}, differing only in
 * that the dispatch commits to the field WITHOUT an explicit {@code this}.
 *
 * <p>That difference is invisible in the source and almost invisible in the AST —
 * Spoon models a bare field read as a {@code CtFieldRead} with an implicit
 * {@code this} target — so the fixture exists to pin that the recognizer keys off
 * the resolved TYPE of the assignment target, never off the spelling.
 */
public sealed interface Latch permits Idle, Armed, Fired {
}
