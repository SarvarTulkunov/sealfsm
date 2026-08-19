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
     */
    POLY_CARRIER
}
