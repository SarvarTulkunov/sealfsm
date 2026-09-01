package foldbinding;

/** The termination control's hierarchy. */
public sealed interface Loop permits Loop.Spin, Loop.Rest {
    record Spin() implements Loop {}
    record Rest() implements Loop {}
}
