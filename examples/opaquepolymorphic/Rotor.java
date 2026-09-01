package opaquepolymorphic;

/**
 * TIER 2 FIXTURE AT THE OTHER LOCUS — the same property as
 * {@code examples/opaquesuccessor}, demonstrated for
 * {@code POLYMORPHIC_OVERRIDE} rather than asserted for it.
 *
 * <p>Every permitted subtype owns a transition method returning the hierarchy
 * root, which is the classic State pattern and the commit the codomain proves
 * {@code DIRECT}ly. Each one computes its successor unrecoverably, and each does
 * so <b>differently</b>: this is the second, independently-sourced generality
 * fixture, not a copy-edit of the centralized one. If only the centralized locus
 * carried this property it would be demonstrated for one idiom and asserted for
 * the rest, which is the gap the FIXLOG's generality requirement exists to close.
 *
 * <p>Must produce: one machine; 3 states, exactly the {@code permits} clause;
 * {@code Encoding.POLYMORPHIC}; {@code CommitEvidence.DIRECT} (the probe is not
 * involved — the codomain is the proof); {@code resolvedTransitionCount() == 0};
 * one unresolved edge per dispatched state, each with a known source, since at
 * this locus the source state is <em>exact</em> and needs no data flow at all —
 * it is the declaring class.
 *
 * <p>That last point is what makes the fixture worth having separately. The
 * centralized locus recovers its source states from arm patterns, so a Tier 2
 * machine there is "we matched the arm and lost the target". Here the source is
 * the declaring type, so a Tier 2 machine is "we know exactly which state each
 * edge leaves, and nothing about where it goes" — the cleanest possible statement
 * that the two claims are independent.
 */
public sealed interface Rotor permits Parked, Spinning, Braking {

    /**
     * The per-state transition method. Its return type is the hierarchy root, so
     * the commit is proven by codomain at every one of the three overrides.
     */
    Rotor advance(int delta);
}
