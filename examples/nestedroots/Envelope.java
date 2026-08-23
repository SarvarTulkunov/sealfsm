package nestedroots;

/**
 * NEGATIVE CONTROL for the re-offer itself: an outer hierarchy that abstains and
 * whose nested sealed child is not a machine either.
 *
 * <p>Re-offering must not manufacture anything. What it must do is give the
 * child its own diagnostic: before the fix the only line printed named
 * {@code Envelope}, and a reader had no way to tell whether {@link Contents}
 * had been examined and rejected or never looked at at all.
 */
public sealed interface Envelope permits Stamp, Contents {
}
