package eventmajor;

/**
 * NEGATIVE CONTROL for the Q × Σ → Q requirement, and the one the real-world census
 * demanded. {@link ShadeFactory} switches over a closed alphabet and <b>two</b> of
 * its arms install a {@code Shade}, so it satisfies the commit and the arm count —
 * but the host holds no hierarchy value at all, so the value it produces cannot
 * depend on a current state. That is a factory, not a transition table.
 *
 * <p>This is the commonest false positive available: over JDK 21 the census found
 * {@code VectorShape.forBitSize(int)}, {@code JavaKind.fromPrimitiveOrVoidTypeChar(char)}
 * and {@code Opcode.getOpcodeBlock(int)} — all of them exactly this. Must yield
 * <b>no machine and no candidate</b>.
 */
public sealed interface Shade permits Pale, Deep { }
