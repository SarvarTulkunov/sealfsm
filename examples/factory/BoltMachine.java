package examples.factory;

/**
 * Centralized transition function whose arms <em>delegate</em> to helper methods
 * rather than constructing the next state inline (finding F3). Bounded
 * inter-procedural summaries fold each helper's return values back into the call
 * site:
 *
 * <ul>
 *   <li>{@code shutOnTurn} — a delegate returning {@code new Shut()} on the guard
 *       and the incoming state otherwise (a self-loop recovered <em>through</em>
 *       the helper);</li>
 *   <li>{@code factoryOpen} — a zero-arg factory returning {@code new Open()};</li>
 *   <li>{@code hopA → hopB → hopC} — a chain whose concrete target sits deeper
 *       than the depth budget (k = 2), so it stays honestly unresolved rather
 *       than being guessed.</li>
 * </ul>
 */
public final class BoltMachine {

    public static Bolt transition(Bolt current, Event event) {
        return switch (current) {
            case Open o   -> shutOnTurn(current, event);   // resolved through the delegate
            case Shut s   -> factoryOpen();                // resolved through the factory
            case Jammed j -> hopA(current, event);         // target beyond budget → unresolved
        };
    }

    static Bolt shutOnTurn(Bolt current, Event event) {
        return event instanceof Turn ? new Shut() : current;
    }

    static Bolt factoryOpen() {
        return new Open();
    }

    static Bolt hopA(Bolt current, Event event) {
        return hopB(current, event);
    }

    static Bolt hopB(Bolt current, Event event) {
        return hopC(current, event);
    }

    static Bolt hopC(Bolt current, Event event) {
        return new Jammed();   // reachable only at inter-procedural depth 3 (> k = 2)
    }
}
