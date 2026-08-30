package mutatorshape;

/**
 * F22 — the shape the finding is about: a mutation-encoded machine whose mutator
 * is named nothing in particular.
 *
 * <p>{@link BoltRig#assume} is a state mutator by every structural measure — one
 * {@code Bolt} parameter, committed to the {@code Bolt} field — and its name is on
 * no list of conventional setter words. Recognising it is the positive statement
 * that discovery is by shape.
 *
 * <p>{@link BoltRig#become} is the trap the word list sprang. It is an audit hook:
 * one {@code Bolt} parameter, and a body that commits nothing. The old recognizer
 * admitted a method whose name was one of {@code setState}/{@code changeState}/
 * {@code transitionTo}/{@code goTo}/{@code setCurrent}/{@code become}
 * <em>regardless of its body</em>, and an admitted method has its call sites'
 * argument published as the committed successor — so {@code rig.become(i)}, the
 * most ordinary bookkeeping there is, became a RESOLVED self-loop on {@code Idle}.
 * A fabricated resolved edge is the one failure mode the soundness invariant
 * forbids outright.
 */
@Fsm
public sealed interface Bolt permits Idle, Live, Spent {
}
