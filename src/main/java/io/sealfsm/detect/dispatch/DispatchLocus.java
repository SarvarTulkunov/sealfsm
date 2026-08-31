package io.sealfsm.detect.dispatch;

/**
 * Where the discrimination of the current state <em>lives</em> — one of the two
 * properties every recognizer in this tool used to hard-code in a pair with the
 * other.
 *
 * <p>Until this package existed, each recognizer fused a locus with a commit
 * form and answered a single yes/no:
 *
 * <pre>
 *   CarrierTransitionDetector          = per-subtype override ∧ carrier return
 *   DispatchCommitDetector             = switch over H        ∧ {return, field, local}
 *   findCentralizedTransitionMethods   = switch over H        ∧ return ∧ H-typed parameter
 *   findFunctionalTransitionCallables  = lambda selector      ∧ return
 * </pre>
 *
 * <p>Fusing them makes valid combinations <em>unreachable by construction</em>,
 * and the gap is not hypothetical: a centralized switch whose arms return a
 * carrier object wrapping the next state is an ordinary way to write a
 * transition table, and no recognizer above can see it — the carrier detector
 * requires a per-subtype override, and the switch detector requires the commit
 * to be an H value. Splitting the pair into two axes makes every combination
 * reachable by composition instead of by adding a fifth recognizer.
 *
 * <p>This axis is <b>not</b> {@link io.sealfsm.model.StateMachine.Encoding}, and
 * must not be folded into it. {@code Encoding} has exactly two positions and is
 * what the thesis stratifies recall by; the locus is finer, and several loci map
 * onto one encoding — see {@link #encoding()}.
 */
public enum DispatchLocus {

    /**
     * One {@code switch} over the hierarchy type, wherever it is hosted:
     * {@code switch (state) { case Idle i -> ... }}. The arms are the cases.
     */
    CENTRALIZED_SWITCH,

    /**
     * One chain of {@code instanceof} tests over a single hierarchy-typed
     * selector: {@code if (s instanceof Idle) ... else if (s instanceof Live)
     * ...}. Semantically the same discrimination as a switch, spelled with
     * {@code if} — which is the dominant shape in Java written before
     * pattern-matching switch, i.e. most Java.
     */
    INSTANCEOF_CHAIN,

    /**
     * Dispatch by virtual call: each permitted subtype overrides the transition
     * method, and the arms are the overrides. The GoF State pattern.
     */
    POLYMORPHIC_OVERRIDE,

    /**
     * The transition function is a lambda or an anonymous-class method rather
     * than a named one. Its body discriminates the state exactly as a named
     * centralized function does.
     */
    FUNCTIONAL_CALLABLE;

    /**
     * The two-position encoding this locus reports as.
     *
     * <p>The mapping is many-to-one on purpose. A switch and an {@code instanceof}
     * chain are one dispatch written two ways; an override and a lambda are one
     * dispatch hosted two ways. Reporting four positions where the thesis claims
     * two would put the spelling of a construct into the axis that is supposed to
     * say where dispatch lives.
     */
    public io.sealfsm.model.StateMachine.Encoding encoding() {
        return switch (this) {
            case CENTRALIZED_SWITCH, INSTANCEOF_CHAIN ->
                    io.sealfsm.model.StateMachine.Encoding.CENTRALIZED_DISPATCH;
            case POLYMORPHIC_OVERRIDE, FUNCTIONAL_CALLABLE ->
                    io.sealfsm.model.StateMachine.Encoding.POLYMORPHIC;
        };
    }
}
