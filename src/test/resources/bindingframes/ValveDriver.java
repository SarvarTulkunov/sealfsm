package bindingframes;

import bindingframes.Valve.Open;
import bindingframes.Valve.Opening;
import bindingframes.Valve.Shut;

/**
 * POSITIVE B — structurally different from A on every axis the task names: a
 * different commit form (CARRIER_RETURN, where A commits by FIELD_MUTATION inside
 * a void callee), TWO hops ({@code relay} then {@code pack}) where A has one, and
 * a successor produced by a STATIC FACTORY where A writes a construction.
 *
 * <p>The factory is what makes this a test of the traversal rather than of one
 * cell. The binding reaches {@code pack}'s hierarchy slot as {@code v}, bound to
 * {@code relay}'s {@code v}, bound to {@code Valves.opening()} — a CALL. F25
 * resolved a bound argument with the pure resolver, which never folds, so the
 * chain ended at "a call — unresolved" although the same call, returned directly,
 * folds and resolves. The bound expression must be read by the caller's own
 * analysis, in the frame it is written in.
 */
public final class ValveDriver {

    public static Step next(Valve current, Cmd c) {
        return switch (current) {
            case Shut s -> relay(Valves.opening(), c);
            case Opening o -> relay(Valves.open(), c);
            case Open o -> relay(Valves.shut(), c);
        };
    }

    /** Hop one: the successor is still only a parameter. */
    private static Step relay(Valve v, Cmd c) {
        return pack(v, c.name());
    }

    /** Hop two: the forwarding factory. Its hierarchy slot is a parameter and nothing else. */
    private static Step pack(Valve v, String note) {
        return new Step(v, note);
    }
}
