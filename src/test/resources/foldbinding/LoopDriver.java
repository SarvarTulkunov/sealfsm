package foldbinding;

import foldbinding.Loop.Rest;
import foldbinding.Loop.Spin;

/**
 * The TERMINATION control for F25. A binding map that resolves a parameter by
 * resolving the caller's argument is a step the analysis can take repeatedly, so
 * a forwarder that forwards to itself must halt — and must halt UNRESOLVED
 * rather than settling on whatever it was last holding.
 *
 * <ul>
 *   <li>{@code Spin} — DIRECT recursion. {@code bounce} calls itself, so the
 *       cycle is visible one frame down.</li>
 *   <li>{@code Rest} — MUTUAL recursion. {@code ping} and {@code pong} forward to
 *       each other, so no single signature repeats until two frames have been
 *       pushed; the guard has to be the stack, not the name.</li>
 * </ul>
 *
 * <p>Both arms are real transitions the source could make and the analysis
 * cannot read, so both must appear as unresolved edges — never dropped, and
 * never resolved to the argument that started the cycle.
 */
public final class LoopDriver {

    public static Loop next(Loop current, Sig event) {
        return switch (current) {
            case Spin s -> bounce(new Rest());
            case Rest r -> ping(new Spin());
        };
    }

    private static Loop bounce(Loop s) {
        return bounce(s);
    }

    private static Loop ping(Loop s) {
        return pong(s);
    }

    private static Loop pong(Loop s) {
        return ping(s);
    }
}
