package mutatorshape;

/**
 * F22, second half — the trap a purely structural rule falls into if it stops at
 * "one hierarchy-typed parameter whose body writes a hierarchy-typed field".
 *
 * <p>{@link VentRig#restart} satisfies exactly that: one {@code Vent} parameter,
 * and an assignment to the {@code Vent} field. Both the old word list and the
 * obvious replacement admit it. But its parameter is the state being <em>left</em>
 * — read to be audited — and what lands in the field is {@code new Sealed()},
 * chosen by the callee. Publishing the call's argument as the successor is a
 * confident claim about a transition the program does not make.
 *
 * <p>Held beside {@link VentRig#assume}, which is a real mutator, so the fixture
 * asserts a NARROWING rather than an absence: the machine keeps the edge it can
 * attribute, and the commit it cannot attribute is RECORDED as unresolved instead
 * of being replaced by a fiction. At HEAD this hierarchy reports a clean-looking
 * 2/2 containing a fabricated {@code Venting -> Venting} and missing the real
 * {@code Venting -> Sealed}.
 *
 * <p>{@code assume} is deliberately spelled the same as {@link BoltRig#assume},
 * in the same model: the two mutators belong to different hierarchies, so the
 * name cannot be what tells the call sites apart. Recognition is keyed on the
 * callee's declaration.
 */
@Fsm
public sealed interface Vent permits Sealed, Venting {
}
