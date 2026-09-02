package eventmajor;

/**
 * NEGATIVE CONTROL for the commit requirement — {@code examples/voidfold} restated
 * at the Σ-major locus. {@link ModeReporter} holds a {@code Mode} field and
 * switches over the same {@link FrameType} in the same shape; its arms simply fold
 * into a {@code String}. Must yield <b>no machine and no candidate</b>.
 *
 * <p>The {@code Mode} field is what makes this control load-bearing: without it the
 * host would hold no hierarchy value and be excluded by the Q × Σ → Q requirement
 * instead, so the control would pass while testing the wrong clause.
 */
public sealed interface Mode permits Fast, Slow { }
