package carrierdispatch;

/**
 * A carrier: exactly ONE hierarchy-typed component, so the successor is in a
 * slot the analysis can name. The action rides along beside it, which is why a
 * transition table returns a wrapper at all rather than the bare state.
 */
public record Step(Signal next, String action) {
}
