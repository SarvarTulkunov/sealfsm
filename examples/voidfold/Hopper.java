package voidfold;

/**
 * NEGATIVE CONTROL for the k = 1 commit-existence probe, and the load-bearing
 * one.
 *
 * <p>Structurally <b>indistinguishable</b> from {@code examples/voidcommit} at
 * the call site: the same four-member sealed hierarchy, the same stateful
 * driver, the same exhaustive {@code switch} statement over the state, the same
 * method names, the same arity, the same matched state passed as the argument.
 * The only difference is inside the callee, which commits to a <b>non-hierarchy</b>
 * field ({@code this.label}) instead of to the state field.
 *
 * <p>Must produce: <b>no machine</b>. One {@code Candidate}, reporting the
 * complete four-state {@code permits} closure and the reason the commit could not
 * be proven. If this fixture ever yields a machine, the probe has become "any
 * exhaustive switch is a state machine" and the precision claim is gone — every
 * sealed sum type in Java is eventually switched over, so {@code String
 * describe(Shape)} would become a three-state automaton, the classifier's
 * confusion matrix would become meaningless, and the precision half of the
 * evaluation would be destroyed.
 *
 * <p>This is {@code examples/foreignfold} one indirection deeper. That fixture
 * pins the codomain guard where the fold is <em>visible at the dispatch</em>
 * ({@code label = switch (state)}); this one pins the same guard where the fold
 * is one call away and the dispatch itself says nothing. The probe must ask about
 * the <em>type</em> of what is installed, never merely whether something is
 * installed — which is why the two fixtures are held as close together as the
 * language allows.
 *
 * <p>The pair also pins the other half: the candidate channel reports this
 * hierarchy's four states in full. Refusing to call it a machine and refusing to
 * say what its states are were the same refusal before Tier 3 existed, and they
 * are two different claims.
 */
public sealed interface Hopper permits Empty, Filling, Full, Jammed { }
