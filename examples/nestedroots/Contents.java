package nestedroots;

/** Sealed, exhaustive, and a plain sum type: no transition producer anywhere. */
public sealed interface Contents extends Envelope permits Letter, Parcel {

    /** A fold to a foreign codomain — the shape {@code examples/shape} pins as NOT a machine. */
    double postageGrams();
}
