package bindingframes;

/** POSITIVE B's hierarchy. */
public sealed interface Valve permits Valve.Shut, Valve.Opening, Valve.Open {
    record Shut() implements Valve {}
    record Opening() implements Valve {}
    record Open() implements Valve {}
}
