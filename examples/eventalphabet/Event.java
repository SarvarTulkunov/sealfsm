package examples.eventalphabet;

/**
 * Closed, compiler-checked input alphabet. {@code Skip} is deliberately ignored
 * by every transition (folded into a {@code default} arm), so it appears on no
 * edge — yet it must still be recovered into Σ from this {@code permits} clause.
 */
public sealed interface Event permits Play, Pause, Stop, Skip {}
