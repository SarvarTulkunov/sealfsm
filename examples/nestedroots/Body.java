package nestedroots;

/**
 * The nested machine — the thing this fixture exists to recover.
 *
 * <p>It is sealed (so the detector withholds it), it is a permitted subtype of
 * a hierarchy that is NOT a machine (so the withholding loses it), and it is
 * explicitly marked (so no reader can argue the tool was right to ignore it).
 *
 * <p>The marker is not decoration. Every value-producing recognizer asks
 * "does something produce a value whose type is in H?", and H(Body) is a subset
 * of H(Message) — so a {@code Body}-returning transition function would keep
 * {@code Message} accepted too, and this fixture would prove nothing. Only the
 * F2 mutation encoding, whose commits return nothing at all, leaves the parent
 * with no producer to find; and a mutation-only hierarchy has to opt in.
 */
@Fsm
public sealed interface Body extends Message permits Empty, Streaming, Complete {
}
