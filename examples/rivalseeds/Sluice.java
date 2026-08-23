package rivalseeds;

/**
 * Fixture for the initial-state UNANIMITY rule, on the strongest of the three
 * heuristics.
 *
 * <p>Rule 3 (a hierarchy-typed local seeded with {@code new Concrete()}) already
 * required every candidate in the model to agree, precisely so that two drivers
 * starting from different states could not have the answer settled by whichever
 * file Spoon walked first. Rule 1 — a hierarchy-typed FIELD seeded the same way,
 * and the rule that outranks it — returned the first field it found.
 *
 * <p>{@link PrimaryDriver} and {@link StandbyDriver} are the rival seeds: both
 * hold a {@code Sluice} field, one opened SHUT and the other FLOWING. Nothing in
 * the program says which is the machine's start, so naming either one is a claim
 * about the analyser's traversal order, not about the code.
 *
 * <p>The relation is deliberately strongly connected — every state is reachable
 * from every other — so the structural rule below has no candidate either and
 * cannot mask the outcome. The honest result is a reported GAP.
 *
 * <p>{@link Damper} is the negative control: two drivers that AGREE must still
 * yield an answer, or the fix would have turned "do not guess" into "give up
 * whenever there is more than one driver".
 */
public sealed interface Sluice {

    record Shut() implements Sluice {}

    record Flowing() implements Sluice {}

    record Blocked() implements Sluice {}
}
