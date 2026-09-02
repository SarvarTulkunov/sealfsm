package eventmajor;

/**
 * NEGATIVE CONTROL for the two-arm threshold. {@link BeatBox} holds a {@code Beat}
 * field and discriminates the shared closed alphabet, so it satisfies every other
 * requirement; exactly <b>one</b> arm installs a {@code Beat}. One committing
 * branch is a special case being handled, not a transition table — the same
 * threshold, for the same reason, that a type-test chain applies when it demands
 * two discriminated branches. Must yield <b>no machine and no candidate</b>.
 */
public sealed interface Beat permits Up, Down { }
