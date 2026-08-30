package throwcarrier;

import java.util.Optional;

/**
 * NEGATIVE CONTROL for F11 on the carrier path, and the load-bearing one.
 *
 * <p>Nothing here is undefined and nothing throws — every arm is an ordinary
 * transition. What makes it a control is that the carrier is a JDK type, so every
 * production goes through {@code Optional.of(...)}, a method Spoon supplies as a
 * reflective SHADOW: a real signature with an empty {@code { }} body. That body
 * holds no {@code return} for the same reason it holds nothing at all — it was
 * never parsed — so read as evidence it says "cannot return normally" about a
 * method that plainly can.
 *
 * <p>F9 already declines on a shadow body, and the carrier path inherits that by
 * calling the same predicate rather than restating it. This hierarchy is what
 * keeps the inheritance honest: ablate the shadow check and this machine reports
 * <strong>0/0</strong> — three states, every edge deleted, no unresolved marker,
 * a whole automaton silently gone.
 *
 * <p>It belongs on the carrier path specifically because a shadow-bodied carrier
 * is ORDINARY here. The centralized fold only ever asks the question of an
 * in-model helper returning the hierarchy type; a carrier is any wrapper at all,
 * so {@code Optional}, {@code List.of} and their kind are routine inputs.
 */
public sealed interface Hoist permits Parked, Raising, Held {

    /** Compute the next hoist state, absent when the lever does nothing. */
    Optional<Hoist> on(Lever lever);
}
