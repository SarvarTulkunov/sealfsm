package rivalseeds;

/**
 * NEGATIVE CONTROL for the unanimity rule on {@link Sluice}.
 *
 * <p>{@link DamperPrimary} and {@link DamperStandby} both seed PARKED. Two
 * seeded fields are present, so the rule's candidate set has more than one
 * member to collect — but they agree, and an agreed answer must still be
 * reported. Without this control, "collect all candidates and require
 * unanimity" and "abstain as soon as a second driver exists" are indistinguish-
 * able, and the second silently costs an initial state on every program that
 * happens to construct its machine twice.
 *
 * <p>Like {@link Sluice} the relation is strongly connected, so the structural
 * rule abstains here too: an answer of PARKED can only have come from the
 * seeded fields.
 */
public sealed interface Damper {

    record Parked() implements Damper {}

    record Cruising() implements Damper {}

    record Halted() implements Damper {}
}
