package valueforms;

/**
 * Fixture for successor <em>form</em> resolution.
 *
 * <p>The dispatch encoding here is unremarkable — polymorphic per-state methods
 * returning a carrier, exactly like {@code examples/tcp}. What it varies is the
 * other axis: how each transition <em>names</em> its successor. Between them the
 * four permitted subtypes cover every form the resolver claims to handle:
 *
 * <ul>
 *   <li>{@code new Firing()} — construction;</li>
 *   <li>{@code Armed.INSTANCE} — a singleton typed as the concrete state;</li>
 *   <li>{@code Idle.INSTANCE} — a singleton typed as the abstract ROOT, resolvable
 *       only through its initializer (a naive reader calls this "stay put");</li>
 *   <li>{@code Phase.RAMP} — an enum constant, where the constant is the state;</li>
 *   <li>a local holding a hierarchy value, assigned in the same method;</li>
 *   <li>{@code this} — a self-loop;</li>
 *   <li>{@code pick(tick)} — computed by a helper, which must stay UNRESOLVED.</li>
 * </ul>
 */
public sealed interface Signal permits Idle, Armed, Firing, Phase {

    /** Compute the step taken when {@code tick} arrives in this state. */
    Step on(Tick tick);
}
