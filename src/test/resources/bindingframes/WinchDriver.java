package bindingframes;

import bindingframes.Winch.Coiled;
import bindingframes.Winch.Slack;
import bindingframes.Winch.Taut;

/**
 * F9 on the CARRIER path may only read the body that runs.
 *
 * <p>{@code gate.refuse(t)} and {@code strict.refuse(c)} are the same method, and
 * its bound body always throws. Through a {@code Gate}-typed receiver another body
 * can run ({@link LenientGate} returns a carrier), so the arm is not an undefined
 * input: it is a gap, recorded. Before F29 F9 read the bound body and DELETED the
 * edge. Through a {@code StrictGate}-typed receiver the body is unique, and F9's
 * proof holds: the {@code Coiled} arm contributes no edge, exactly as before.
 */
public final class WinchDriver {

    public static Haul step(Winch current, Gate gate, StrictGate strict) {
        return switch (current) {
            case Slack s -> new Haul(new Taut());
            case Taut t -> gate.refuse(t);
            case Coiled c -> strict.refuse(c);
        };
    }
}
