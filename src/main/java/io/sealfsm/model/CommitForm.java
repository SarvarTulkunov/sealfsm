package io.sealfsm.model;

/**
 * How a recovered transition <em>commits</em> its successor: the mechanism by
 * which the next state, once chosen, becomes the machine's state.
 *
 * <p>This is the third and last axis of the taxonomy, and it is orthogonal to the
 * other two:
 * <ul>
 *   <li>{@link StateMachine.Encoding} — where dispatch <em>lives</em> (two
 *       positions: one central switch, or a method per permitted subtype);</li>
 *   <li>{@link SuccessorForm} — how the chosen successor is <em>spelled</em>
 *       ({@code new X()}, a singleton, an enum constant, {@code this}, ...);</li>
 *   <li>{@code CommitForm} — how the chosen successor is <em>installed</em>
 *       (returned as a value, written to a field, accumulated in a local, or
 *       handed to a carrier object).</li>
 * </ul>
 *
 * <p>Keeping the three apart is what lets the evaluation report recall
 * <em>stratified by idiom</em>: the same centralized switch reaches
 * {@code return switch (state) {...}} and
 * {@code this.state = switch (this.state) {...}} through the same dispatch
 * analysis but different commit analysis, so a recall gap can be attributed to
 * the one that actually caused it rather than pooled into "centralized".
 *
 * <p>A machine may exhibit several commit forms at once (one method returns, a
 * second mutates), so it is recorded as a set per machine and per edge.
 */
public enum CommitForm {

    /**
     * The successor is the value of the transition method:
     * {@code return switch (state) { ... };}. The classic pure-function shape.
     */
    VALUE_RETURN,

    /**
     * The successor is written to a hierarchy-typed field:
     * {@code this.currentState = switch (this.currentState) { ... };}, or the
     * same without the explicit {@code this}. Includes the GoF {@code Context}
     * mutator ({@code ctx.setState(new Locked())}), which installs the successor
     * into the context's field one call away.
     */
    FIELD_MUTATION,

    /**
     * The successor passes through a hierarchy-typed local before being
     * installed: {@code State next = switch (state) { ... };} or a local assigned
     * on several branches and committed once at the end. The local is an
     * accumulator, not a state of its own.
     */
    LOCAL_ACCUMULATOR,

    /**
     * The successor is an argument to a shallow, non-hierarchy carrier object
     * returned by a per-state method:
     * {@code return Transition.to(new LastAck(), Action.SND_FIN);}.
     *
     * <p>This is {@link #CARRIER_RETURN} observed at the
     * {@code POLYMORPHIC_OVERRIDE} locus. The two are one commit mechanism, and
     * the only reason they are separate values is continuity: the thesis's
     * stratified recall table is keyed on this enum and already names
     * {@code POLY_CARRIER}. Collapsing them is a one-line change plus a golden
     * re-baseline, and it is the thesis author's call, not the refactor's.
     */
    POLY_CARRIER,

    /**
     * The successor is carried out of the dispatch inside a wrapper the analysis
     * can see through: the method returns some type R outside the hierarchy, and R
     * has exactly one hierarchy-typed component.
     *
     * <pre>{@code
     *   record Step(TcpState next, Action action) { }
     *
     *   static Step next(TcpState s, Event e) {
     *       return switch (s) {                       // CENTRALIZED_SWITCH
     *           case Listen l -> new Step(new SynReceived(), Action.SND_SYN_ACK);
     *           ...
     *       };
     *   }
     * }</pre>
     *
     * <p>Reachable at <em>every</em> locus, which is the point. Before the axes
     * were split this combination existed in no recognizer: the carrier detector
     * required a per-subtype override, and the switch detector required the commit
     * to be a hierarchy value, so a centralized transition table whose arms return
     * a carrier matched neither and the whole hierarchy was lost.
     *
     * <p>The single-component requirement is what keeps the exhaustive-fold guard
     * intact. {@code String describe(State s) { return switch (s) {...}; }} folds
     * into a type with no hierarchy-typed component and is rejected exactly as
     * before; several such components are ambiguous about which one is the
     * successor, so the commit is declined rather than guessed — the same rule
     * {@code soleEnumComponent} applies to &Sigma;.
     */
    CARRIER_RETURN,

    /**
     * The successor is <em>handed to a mutator</em>, which installs it:
     * {@code ctx.setState(new Filling());}. The commit is one call away, and what
     * makes the call's argument the successor is that the callee commits
     * <em>what it was handed</em> — not that it is named like a setter.
     *
     * <p>Split out of {@link #FIELD_MUTATION}, which used to absorb it. They are
     * not the same claim: a field mutation is a write the analysis can SEE, an
     * exact observation of the immediate syntactic context; a mutator argument
     * rests additionally on a structural reading of the callee's body
     * ({@link io.sealfsm.detect.dispatch.MutatorRecognizer}). Pooling the two
     * reported an inference and an observation under one label, so a recall or
     * precision gap in the inference was unattributable — which is the exact
     * failure the stratified table exists to prevent.
     *
     * <p>Reachable at every locus, which is the gain. Before the axes were split
     * a dispatch committing this way was not a recognised dispatch at all: it was
     * rescued only by the whole-hierarchy mutation fallback, and that runs
     * <em>only when nothing else found anything</em>, so a hierarchy with one
     * value-returning producer alongside lost every mutator commit it had.
     */
    MUTATOR_ARGUMENT
}
