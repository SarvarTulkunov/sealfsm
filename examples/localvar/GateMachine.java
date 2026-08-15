package examples.localvar;

/**
 * Centralized transition function that computes the next state through a
 * <em>reassigned local</em> instead of returning it directly (finding F1).
 *
 * <p>In each arm the local {@code next} is declared with the sealed-root type
 * {@code Gate} and seeded with the incoming {@code current}; a guarded
 * assignment then overwrites it with a concrete state before it is yielded. The
 * naive resolver, seeing only the local's declared root type at the {@code yield
 * next}, emits a single guardless self-loop and loses the {@code new Open()} /
 * {@code new Closed()} edge. The reaching-definitions pass must recover:
 *
 * <ul>
 *   <li>{@code Closed → Open} guarded by {@code event instanceof Push};</li>
 *   <li>{@code Closed → Closed} guarded by {@code !(event instanceof Push)};</li>
 *   <li>the mirror pair from {@code Open}.</li>
 * </ul>
 *
 * and must <em>not</em> emit an unguarded self-loop from either state.
 */
public final class GateMachine {

    public static Gate transition(Gate current, Event event) {
        return switch (current) {
            case Closed c -> {
                Gate next = current;                 // root-typed local, will be reassigned
                if (event instanceof Push) {
                    next = new Open();               // real target on the guard
                }
                yield next;                          // {Open when Push, self-loop otherwise}
            }
            case Open o -> {
                Gate next = current;
                if (event instanceof Push) {
                    next = new Closed();
                }
                yield next;
            }
        };
    }
}
