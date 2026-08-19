package examples.eventalphabet;

/**
 * Centralized transition function that dispatches first on the state and then on
 * the event (finding F4). Each inner {@code switch (event)} arm labels its edge
 * with the matched event, so parallel edges between the same two states no longer
 * collapse — {@code Stopped --Play--> Playing} and {@code Stopped --Pause-->
 * Playing} are distinct. {@code Skip} is folded into every {@code default} and so
 * carries no edge, yet Σ = {Play, Pause, Stop, Skip} is recovered in full from the
 * sealed {@link Event} type.
 */
public final class PlayerMachine {

    public static Player transition(Player current, Event event) {
        return switch (current) {
            case Stopped s -> switch (event) {
                case Play p   -> new Playing();
                case Pause pa -> new Playing();   // both Play and Pause start playback
                default       -> current;          // Stop, Skip ignored while stopped
            };
            case Playing pl -> switch (event) {
                case Pause pa -> new Paused();
                case Stop st  -> new Stopped();
                default       -> current;          // Play, Skip ignored while playing
            };
            case Paused pa2 -> switch (event) {
                case Play p  -> new Playing();
                case Stop st -> new Stopped();
                default      -> current;           // Pause, Skip ignored while paused
            };
        };
    }
}
