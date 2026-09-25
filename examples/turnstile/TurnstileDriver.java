package examples.turnstile;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@link TurnstileMachine#transition} a transition
 * rather than a conversion. Unseeded on purpose, so the initial-state heuristics
 * see nothing new.
 */
final class TurnstileDriver {
    private Turnstile turnstile;

    TurnstileDriver(Turnstile start) {
        this.turnstile = start;
    }

    void accept(Event event) {
        turnstile = TurnstileMachine.transition(turnstile, event);
    }
}
