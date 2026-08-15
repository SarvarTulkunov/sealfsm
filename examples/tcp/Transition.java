package tcp;

import java.util.List;

/**
 * The result of applying an {@link Event} to a {@link TcpState}: the next state
 * plus the list of {@link Action}s the caller must carry out.
 *
 * <p>Keeping the next state as a concrete {@link TcpState} value (rather than a
 * mutation) is what lets a static analyzer read {@code Transition.to(new X(), ...)}
 * and recover the edge {@code this -> X}.
 */
public record Transition(TcpState next, List<Action> actions) {

    /** Move to {@code next}, performing {@code actions} (or NONE if empty). */
    public static Transition to(TcpState next, Action... actions) {
        return new Transition(
                next,
                actions.length == 0 ? List.of(Action.NONE) : List.of(actions));
    }

    /** Stay in the current state; no action. */
    public static Transition ignore(TcpState current) {
        return new Transition(current, List.of(Action.NONE));
    }
}
