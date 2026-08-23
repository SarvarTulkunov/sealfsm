package rivalseeds;

/**
 * NEGATIVE CONTROL for the flyweight-singleton exclusion, and the reason that
 * exclusion is not cosmetic.
 *
 * <p>Requiring the seeded-FIELD rule to be unanimous is only safe if its
 * candidate set holds actual seeds. A state's own flyweight instance is
 * hierarchy-typed and has a constructor-call default, so it reached the rule
 * looking exactly like a driver's current-state field — and here it would
 * disagree with the one real driver. Unanimity would then abstain, and a
 * machine whose start state IS stated in the source would report a gap: the fix
 * for a fabricated answer turned into a lost one.
 *
 * <p>{@link RatchetDriver} is the only thing in this hierarchy that says where
 * the machine begins ({@code FREE}). The two {@code INSTANCE} constants say
 * only that the states are flyweights, which every state may be. The relation
 * is strongly connected, so {@code Free} cannot come from the structural rule.
 */
public sealed interface Ratchet {

    record Locked() implements Ratchet {
        /** Declared on the state it constructs — a flyweight, not a seed. */
        static final Ratchet INSTANCE = new Locked();
    }

    record Free() implements Ratchet {
        static final Ratchet INSTANCE = new Free();
    }
}
