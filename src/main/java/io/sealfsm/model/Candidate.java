package io.sealfsm.model;

import java.util.Collections;
import java.util.List;

/**
 * A sealed hierarchy the tool <em>refuses to call a state machine</em> and whose
 * states it nevertheless reports in full.
 *
 * <p>This is Tier 3 of the three-outcome classification, and it exists because
 * the tool makes two claims of different strength and used to couple them. State
 * enumeration is exact by construction — the {@code permits} clause is
 * compiler-checked — whereas the transition relation is recovered by
 * intra-procedural data flow and is approximate. Before this type existed, a
 * hierarchy whose transition producer no recognizer matched was dropped at
 * {@code Analyzer}'s classification gate before {@code StateExtractor} was ever
 * called, so the tool reported <b>zero states</b> for a hierarchy whose states
 * were never in doubt. That silently downgraded claim 1 to "states are exact
 * <em>when transitions resolve</em>", which is a materially weaker claim and one
 * the tool does not need to make.
 *
 * <p><b>A candidate is not a machine and must never be counted as one.</b> It is
 * carried on {@link ExtractionResult#candidates()}, never on
 * {@link ExtractionResult#machines()}, and no {@code .dot} or {@code .scxml} is
 * written for it: an automaton drawn with a complete state set and an empty
 * transition relation is a picture of a claim the analysis did not make. The
 * separation is the point — the line between a Tier 2 machine (dispatch present,
 * commit <em>proven</em>, successors unrecoverable) and a Tier 3 candidate
 * (dispatch present, commit <em>not</em> proven) is the tool's honest scope
 * boundary, and {@code examples/voidfold} is the fixture that holds it: it is
 * indistinguishable from a real machine at the call site and differs only in the
 * declared type of the field its callee writes.
 *
 * @param name           the root's simple name
 * @param qualifiedName  the root's fully-qualified name
 * @param topLevelStates the complete {@code permits} closure, nested exactly as
 *                       {@link StateMachine#topLevelStates()} nests it — composite
 *                       states hold their children, and a permitted enum holds its
 *                       constants. Exactly the enumeration the machine path would
 *                       have produced, not a flattened approximation of it
 * @param dispatchSites  one rendered description per discrimination of the state
 *                       found for this root ({@code locus @ host}), so a reader can
 *                       go and look at the code the tool could not prove a commit for
 * @param reason         why the commit could not be proven
 */
public record Candidate(String name, String qualifiedName, List<State> topLevelStates,
                        List<String> dispatchSites, String reason) {

    public Candidate {
        topLevelStates = List.copyOf(topLevelStates);
        dispatchSites = List.copyOf(dispatchSites);
    }

    /**
     * Depth-first flattening of the state hierarchy — the same view
     * {@link StateMachine#allStates()} gives, through the same helper, so a test
     * comparing a candidate's state set against a machine's is comparing like with
     * like rather than two spellings of "flatten".
     */
    public List<State> allStates() {
        return State.flatten(topLevelStates);
    }

    @Override
    public List<State> topLevelStates() {
        return Collections.unmodifiableList(topLevelStates);
    }
}
