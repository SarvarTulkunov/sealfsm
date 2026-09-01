package foldbinding;

import foldbinding.Flow.Halted;
import foldbinding.Flow.Ready;
import foldbinding.Flow.Running;

/**
 * F25 — the fold carries the caller's argument bindings, so a successor that
 * arrives through a folded helper's PARAMETER is resolvable.
 *
 * <p>Every arm hands the successor to a helper and reads it back out of a
 * parameter, and the four helpers vary only in what the fold is then asked to
 * do with that parameter. Held on one class deliberately: no difference of file
 * or context can stand in for the body.
 *
 * <ul>
 *   <li>{@code forward} — the bare value forwarder. The successor was in hand at
 *       the call site; entering the callee must not discard it.</li>
 *   <li>{@code pick} — a SELECTION helper. Both parameters are live, each on its
 *       own branch, so the fold must yield BOTH targets under opposite guards
 *       rather than collapsing them or choosing by position.</li>
 *   <li>{@code shadow} — the NEGATIVE CONTROL for the binding. Its parameter is
 *       REASSIGNED before the return, so what it holds there is no longer the
 *       argument and the binding would be a claim about an overwritten value.
 *       Must stay unresolved.</li>
 *   <li>{@code supplier.supply} — the NEGATIVE CONTROL for an unsummarisable
 *       callee: an abstract declaration with no body. Unchanged by F25, and it
 *       is the shape that must NOT start resolving from the argument list.</li>
 * </ul>
 */
public final class FlowDriver {

    /** Never assigned: only its DECLARATION matters, and that declaration is abstract. */
    static Supply supplier;

    public static Flow next(Flow current, Sig event) {
        return switch (current) {
            case Ready r -> forward(new Running());
            case Running x -> pick(new Halted(), new Ready(), event == Sig.STOP);
            case Halted h -> switch (event) {
                case GO -> shadow(new Ready());
                case STOP -> supplier.supply(new Running());
                case RESET -> new Halted();
            };
        };
    }

    /** The bare value forwarder: `s` is exactly what the caller passed. */
    private static Flow forward(Flow s) {
        return s;
    }

    /** Two live parameters on opposite branches; neither may stand in for the other. */
    private static Flow pick(Flow a, Flow b, boolean c) {
        return c ? a : b;
    }

    /** The argument is overwritten before it is read back; the binding is void. */
    private static Flow shadow(Flow s) {
        s = new Running();
        return s;
    }
}
