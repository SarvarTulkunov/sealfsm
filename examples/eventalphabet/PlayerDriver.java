package examples.eventalphabet;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@link PlayerMachine#transition} a transition rather
 * than a conversion. Unseeded on purpose, so the initial-state heuristics see
 * nothing new.
 */
final class PlayerDriver {
    private Player player;

    PlayerDriver(Player start) {
        this.player = start;
    }

    void fire(Event event) {
        player = PlayerMachine.transition(player, event);
    }
}
