package chaindispatch;

/**
 * The other half of the finding: a chain in a method the tool <em>already</em>
 * recognised. {@code ShutterLogic.next(Shutter, Command)} is hierarchy-in /
 * hierarchy-out, so the signature-based recognizer always accepted it — and then
 * every type test was walked as a data guard, leaving each edge with an
 * {@code <unknown>} or {@code <entry>} source and an unresolvable target. A fully
 * recognised machine reported 0 of 5 transitions. Recognition was never the whole
 * gap; attribution was the other half of it.
 */
public sealed interface Shutter permits Open, Closing, Shut {
}
