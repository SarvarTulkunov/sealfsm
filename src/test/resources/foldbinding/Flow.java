package foldbinding;

/**
 * VALUE_RETURN half of the F25 fixture: the successor arrives through a folded
 * helper's PARAMETER, which is the read the fold used to be unable to resolve.
 */
public sealed interface Flow permits Flow.Ready, Flow.Running, Flow.Halted {
    record Ready() implements Flow {}
    record Running() implements Flow {}
    record Halted() implements Flow {}
}
