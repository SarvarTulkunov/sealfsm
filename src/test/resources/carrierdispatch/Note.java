package carrierdispatch;

/**
 * The NEGATIVE CONTROL for the exhaustive-fold guard: a record with no
 * hierarchy-typed component at all. A switch over Signal folding into this is
 * an exhaustive fold, not a transition table, and must stay rejected — the
 * commit requirement is the only thing separating the two, since the two are
 * identical at the discrimination.
 */
public record Note(String text, int count) {
}
