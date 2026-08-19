package io.sealfsm.model;

/**
 * How the successor state of a transition was <em>written</em> in the source.
 *
 * <p>This is deliberately orthogonal to {@link StateMachine.Encoding}. The
 * encoding axis records where dispatch <em>lives</em> — a centralized switch or
 * a polymorphic per-state method — and there are exactly two positions on it.
 * The form axis records how the chosen successor is <em>named</em>, and the same
 * set of forms occurs under either encoding: a centralized switch arm can yield
 * a singleton just as a per-state method can, and both can hand the value to a
 * carrier. Resolving a form is therefore one uniform sub-procedure
 * ({@link io.sealfsm.extract.TransitionResolver}) rather than a property of the
 * encoding, and conflating the two axes would report the same recognizer
 * capability twice.
 *
 * <p>Recorded per edge and aggregated per machine so the evaluation can attribute
 * a recall gap to the form that caused it rather than to the encoding it happened
 * to appear under.
 */
public enum SuccessorForm {

    /** {@code new Locked()} — direct construction of a permitted subtype. */
    CONSTRUCTION,

    /**
     * A field read whose identity is fixed by its declared concrete type or by its
     * initializer: {@code return Idle.INSTANCE;} where
     * {@code static final Signal INSTANCE = new Idle();}.
     */
    SINGLETON_FIELD,

    /** A constant of a permitted {@code enum} subtype: {@code return Phase.RAMP;}. */
    ENUM_CONSTANT,

    /** {@code this}, or the unmodified dispatch selector — a self-loop. */
    SELF,

    /**
     * A local holding a hierarchy value, resolved intra-procedurally from its
     * declaration initializer or its reaching definitions.
     */
    LOCAL_VARIABLE,

    /** An explicit cast pinning the target: {@code return (Locked) current;}. */
    CAST
}
