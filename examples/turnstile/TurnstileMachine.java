package examples.turnstile;

/**
 * Centralized transition function written with imperative {@code if} guards
 * inside each state arm — the common shape a flat return-scan misses. The value
 * produced inside the {@code if} is guarded by the condition; the fall-through
 * {@code yield} keeps the current state (self-loop).
 */
public final class TurnstileMachine {

    public static Turnstile transition(Turnstile state, Event event) {
        return switch (state) {
            case Locked l -> {
                if (event instanceof Coin) {
                    yield new Unlocked();
                }
                yield l;
            }
            case Unlocked u -> {
                if (event instanceof Push) {
                    yield new Locked();
                }
                yield u;
            }
        };
    }
}
