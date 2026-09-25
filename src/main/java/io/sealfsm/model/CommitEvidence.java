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
 * <p>F36 (thesis Decision 4) adds {@link #VIA_CALLER}: a returned successor
 * counts as committed only once a caller is seen installing it.
 *
 * <p>It is deliberately <b>not</b> a fourth position on the three-axis taxonomy
 * (encoding / successor form / commit form). Those three classify how a machine
 * is <em>written</em>; this records how much the analysis had to open in order to
 * see it, which is a property of the analysis rather than of the program — the
 * same reason {@link State#isDeclarationUnread()} is not an axis either.
 */
public enum CommitEvidence {

    /**
     * Observed inside the host itself: the successor is assigned to an H-typed
     * field, handed to a recognised mutator, installed into a context (F33),
     * accumulated in a local that the host then stores, or re-entered as the
     * selector of a run-to-completion driver (F34). Nothing outside the host method
     * was needed to see the installation.
     *
     * <p>The strongest of the four.
     */
    DIRECT,

    /**
     * The host RETURNS its successor, and a caller was read to see it installed
     * (F36, thesis Decision 4): stored back into the variable or root-typed field
     * the state is read from, handed to a mutator, or re-entered into a
     * run-to-completion driver. The codomain proves only that a hierarchy value is
     * <em>produced</em>. A conversion within a sum type has the same codomain, and
     * only the caller's store-back separates a transition from it.
     *
     * <p>Before F36 every value-returning machine was reported {@link #DIRECT}, on
     * its codomain alone. That was the precision gap {@code LIMITATIONS.md} L3
     * recorded.
     */
    VIA_CALLER,

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
     * Not established. The hierarchy is a {@link Candidate}, not a machine. Its
     * members are listed provisionally and its dispatch sites are named, but no
     * transition relation is claimed and the members are not recovered FSM states.
     */
    UNPROVEN;

    /**
     * The weaker of two pieces of evidence: the one a reader of a recall table
     * must not overstate. The declaration order is the strength order.
     * {@link #VIA_CALLER} ranks above {@link #VIA_CALLEE} because the probe never
     * chases a successor, so a machine it proves carries unresolved edges by
     * construction.
     */
    public CommitEvidence weaker(CommitEvidence other) {
        return other != null && other.ordinal() > ordinal() ? other : this;
    }
}
