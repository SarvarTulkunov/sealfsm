package io.sealfsm.model;

/**
 * <em>How</em> the commit was established for a hierarchy — not which mechanism
 * installs the successor ({@link CommitForm}), but on what evidence the analysis
 * concluded that a successor is installed at all.
 *
 * <p>The two questions are separate and are answered by separate code, so
 * pooling them makes a later precision or recall gap unattributable. That is the
 * same argument that split {@link CommitForm#MUTATOR_ARGUMENT} out of
 * {@link CommitForm#FIELD_MUTATION}: a field write is a commit the analysis
 * <em>sees</em>, while a mutator argument additionally rests on a structural
 * reading of the callee's body. This axis carries the same distinction one level
 * up, where it applies to every commit form at once.
 *
 * <p>It is deliberately <b>not</b> a fourth position on the three-axis taxonomy
 * (encoding / successor form / commit form). Those three classify how a machine
 * is <em>written</em>; this records how much the analysis had to open in order to
 * see it, which is a property of the analysis rather than of the program — the
 * same reason {@link State#isDeclarationUnread()} is not an axis either.
 */
public enum CommitEvidence {

    /**
     * Observed in the dispatch's own syntactic context: the switch is returned
     * from a method whose codomain is in H, assigned to an H-typed field or local,
     * or handed to a recognised mutator. Nothing outside the host method was read.
     *
     * <p>This is the evidence every machine in the corpus rests on, and it is the
     * strongest of the three.
     */
    DIRECT,

    /**
     * Established by opening exactly one callee body — the k = 1 commit-existence
     * probe. The dispatch's arms are bare calls whose value the language discards
     * (JLS §14.8), so nothing at the call site could prove or disprove a commit;
     * the callee was read, and it installs a hierarchy value.
     *
     * <p>Weaker than {@link #DIRECT} in one specific way that must not be
     * forgotten when reading a recall table: the probe answers <em>whether</em> a
     * successor is installed and deliberately never asks <em>which</em>, so a
     * machine established this way carries unresolved edges by construction unless
     * some other path resolves them.
     */
    VIA_CALLEE,

    /**
     * Not established. The hierarchy is a {@link Candidate}, not a machine: its
     * states are reported (they come from {@code permits} and are exact regardless)
     * and its dispatch sites are named, but no transition relation is claimed.
     */
    UNPROVEN
}
