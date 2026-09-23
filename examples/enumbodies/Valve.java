package enumbodies;

/**
 * The same question asked of a nested SEALED composite rather than an enum, to
 * show the rule is about composites and not about enums. {@link Open} declares a
 * default {@code toggle()} that switches on {@code this}; {@link Open.Stuck}
 * overrides it, so the default runs in Half, Full and Leaky only, and each arm
 * belongs to the member it matches — Leaky reaching the {@code default}.
 */
public sealed interface Valve permits Shut, Open {
    Valve toggle();
}
