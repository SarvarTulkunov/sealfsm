package examples.gofcontext;

/**
 * GoF State-pattern hierarchy (finding F2). Each state implements {@link #handle}
 * and drives the transition by <em>mutating</em> the {@link PortalContext} state
 * field through {@code ctx.setState(...)} — there is no return-based transition
 * method, so the hierarchy opts in with the local {@link Fsm} marker.
 *
 * <p>The recovered FSM mirrors the return-based {@code examples/door}, proving
 * the mutation and functional encodings converge on the same machine:
 *
 * <ul>
 *   <li>{@code Closed → Locked} (guarded by {@code Lock}) and {@code Closed → Open};</li>
 *   <li>{@code Open → Closed};</li>
 *   <li>{@code Locked → Closed} (guarded by {@code Unlock}) and the {@code Locked}
 *       self-loop.</li>
 * </ul>
 */
@Fsm
public sealed interface Portal permits Open, Closed, Locked {
    void handle(PortalContext ctx, Event event);
}
