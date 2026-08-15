package valueforms;

/** The carrier: a successor plus its cost. Deliberately not part of the hierarchy. */
public record Step(Signal next, int cost) {

    public static Step to(Signal next) {
        return new Step(next, 1);
    }

    public static Step stay(Signal current) {
        return new Step(current, 0);
    }
}
