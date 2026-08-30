package explicitimport;

/**
 * The FABRICATION CONTROL. Identical to `samepkg` except that the producer
 * imports a foreign `ext.Amber`, so its `new Amber()` denotes a type that is NOT
 * the permitted state. Recovery must decline it: admitting it would publish a
 * resolved edge to a type the analysis never established, which is the one
 * failure mode the soundness invariant forbids outright.
 */
public sealed interface Light permits Red, Amber {
    Light next();
}
