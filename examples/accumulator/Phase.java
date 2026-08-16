package examples.accumulator;

/**
 * Fixture for the local-accumulator commit idiom (Part A, A2d) and for the F1
 * branch-assignment hazard (Part D, D4).
 */
public sealed interface Phase permits Ready, Working, Blocked, Halted {
}
