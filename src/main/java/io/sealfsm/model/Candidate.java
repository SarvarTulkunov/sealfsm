package io.sealfsm.model;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * A sealed hierarchy the tool <em>refuses to call a state machine</em>, reported
 * because a supported dispatch pattern supplied plausible evidence and the
 * transition relation could not be established.
 *
 * <p><b>A candidate is a provisional, uncertain classification. It is not a
 * detected FSM, and its members are not recovered FSM states</b> (thesis
 * Decisions 1 and 4). It is carried on {@link ExtractionResult#candidates()},
 * never on {@link ExtractionResult#machines()}. No {@code .dot} or {@code .scxml}
 * is written for it, and it must never enter a measured state count or a
 * transition count. An automaton drawn with a complete member set and an empty
 * relation would be a picture of a claim the analysis did not make.
 *
 * <p>What it does carry is the answer to "if this is a machine, what would its
 * states be?". That comes from the same {@code StateExtractor} call a machine's
 * states come from, so the listing is exact as a statement about the type
 * structure. It is kept because it is what an evaluator needs to check the
 * candidate against independently labelled ground truth, and because the members
 * of a hierarchy are what a reader must look at to decide it. {@link #basis()}
 * says which half of the evidence was missing, and {@link #reason()} says it in
 * words.
 *
 * <p>The candidate channel opens only on plausible evidence of a DISPATCH: a
 * discrimination of the state, a Σ-major commit, or a family of value-returning
 * per-state sites that fixes at least two source states. A lone uncalled
 * converter, and a hierarchy nothing examines, are plain abstentions. An
 * established conversion (every caller uses the result as data) is a rejection,
 * never a candidate: Decision 4 forbids turning a known conversion into a reported
 * member set.
 *
 * @param name           the root's simple name
 * @param qualifiedName  the root's fully-qualified name
 * @param topLevelStates the provisional members: the complete {@code permits}
 *                       closure, nested exactly as {@link StateMachine#topLevelStates()}
 *                       would nest it. These are would-be states under the
 *                       candidate's own evidence, not recovered FSM states
 * @param dispatchSites  one rendered description per site weighed for this root
 *                       ({@code locus @ host}), so a reader can go and look at the
 *                       code the tool could not establish a commit for
 * @param reason         why the relation could not be established
 * @param basis          which evidence was missing. Several can apply at once
 */
public record Candidate(String name, String qualifiedName, List<State> topLevelStates,
                        List<String> dispatchSites, String reason, Set<Basis> basis) {

    /** Which half of the evidence a candidate is missing. */
    public enum Basis {
        /**
         * State-major (F26, F32). The state is discriminated, and no commit is
         * proven at the discrimination: the branches fold into a foreign type,
         * compose, or call methods whose commit lies deeper than one call.
         */
        COMMIT_UNPROVEN,
        /**
         * Σ-major (F27). A commit is proven, and the state is discriminated
         * nowhere, so no successor can be attributed to a source state.
         */
        SOURCE_UNATTRIBUTED,
        /**
         * F36, thesis Decision 4. A value-returning dispatch produces hierarchy
         * values, and no caller in the source set is shown installing the result
         * as the current state. A conversion and a state update cannot be told
         * apart here.
         */
        INSTALLATION_UNSHOWN
    }

    public Candidate {
        topLevelStates = List.copyOf(topLevelStates);
        dispatchSites = List.copyOf(dispatchSites);
        basis = basis == null || basis.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(Basis.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(basis));
    }

    /** Always true, and stated as a method so every consumer has to see it. */
    public boolean isProvisional() {
        return true;
    }

    /**
     * Depth-first flattening of the provisional member hierarchy. It is the same
     * view {@link StateMachine#allStates()} gives, through the same helper, so a
     * test comparing a candidate's member set against a machine's state set is
     * comparing like with like rather than two spellings of "flatten".
     */
    public List<State> allStates() {
        return State.flatten(topLevelStates);
    }

    /** The provisional members' direct branches: the root's {@code permits} clause. */
    public List<State> directBranches() {
        return topLevelStates();
    }

    /** The provisional atomic members: the leaves of the expansion, each counted once. */
    public List<State> atomicStates() {
        return State.atomic(topLevelStates);
    }

    /** The provisional grouping nodes: sealed members and enums with constants. */
    public List<State> compositeNodes() {
        return State.composites(topLevelStates);
    }

    @Override
    public List<State> topLevelStates() {
        return Collections.unmodifiableList(topLevelStates);
    }
}
