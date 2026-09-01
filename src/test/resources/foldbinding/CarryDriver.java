package foldbinding;

import java.util.List;

import foldbinding.Carry.Docked;
import foldbinding.Carry.Parked;
import foldbinding.Carry.Rolling;

/**
 * F25 at the CARRIER_RETURN cell, which is where the missing binding cost most:
 * the successor is an ARGUMENT to a wrapper, so a forwarding factory puts it
 * behind a parameter read by construction.
 *
 * <ul>
 *   <li>{@code Parked} — the forwarding factory, one hop. {@code wrap(new
 *       Rolling(), List.of())} has the successor in hand at the call site;
 *       reaching {@code new Carriage(s, a)} inside {@code wrap} and taking the
 *       hierarchy slot finds {@code s}, and only the binding says what {@code s}
 *       is.</li>
 *   <li>{@code Rolling} — the THREE-HOP chain: dispatch arm → per-event helper →
 *       forwarding factory. The successor now has to survive two frames.</li>
 *   <li>{@code Docked} — the NEGATIVE CONTROL, and the sharp one. {@code
 *       reject(new Parked(), event)} is syntactically indistinguishable from
 *       {@code wrap(new Parked(), ...)}: a call whose own type is outside the
 *       hierarchy carrying a hierarchy-typed argument. Only the callee's body
 *       separates them, and F9 must be asked FIRST — with the binding in place,
 *       accepting it would fabricate a resolved edge on every input the source
 *       rejects, which is exactly what an undefined cell looks like.</li>
 * </ul>
 */
public final class CarryDriver {

    public static Carriage step(Carry current, Sig event) {
        return switch (current) {
            case Parked p -> wrap(new Rolling(), List.of());
            case Rolling r -> onRolling(event);
            case Docked d -> reject(new Parked(), event);
        };
    }

    /** Hop two of the three-hop chain: still no successor in sight, only a call. */
    private static Carriage onRolling(Sig e) {
        return switch (e) {
            case STOP -> wrap(new Docked(), List.of("brake"));
            case GO, RESET -> wrap(new Parked(), List.of());
        };
    }

    /** The forwarding factory. Its hierarchy slot is a parameter and nothing else. */
    private static Carriage wrap(Carry s, List<String> a) {
        return new Carriage(s, a);
    }

    /** Cannot return normally (JLS 8.4.7), so it wraps nothing and yields nothing. */
    private static Carriage reject(Carry s, Sig e) {
        throw new IllegalStateException("undefined: " + s + " on " + e);
    }
}
