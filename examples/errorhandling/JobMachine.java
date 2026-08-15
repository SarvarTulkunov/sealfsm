package examples.errorhandling;

/**
 * Centralized transition function whose producers live inside exceptional and
 * iterative control flow (finding F6).
 *
 * <ul>
 *   <li>{@code Running}: the normal next state is produced in the {@code try}
 *       body; the {@code catch} produces the error transition. Both were dropped
 *       before the walker descended {@code try}/{@code catch}.</li>
 *   <li>{@code Waiting}: the next state is produced inside a {@code while} body —
 *       a loop merely repeats the same transition, so its body is descended.</li>
 *   <li>{@code Done}/{@code Failed}: terminal self-loops.</li>
 * </ul>
 */
public final class JobMachine {

    public static Job transition(Job state) {
        return switch (state) {
            case Running r -> {
                try {
                    yield new Done();          // normal completion (inside try)
                } catch (Exception e) {
                    yield new Failed();        // error transition (inside catch)
                }
            }
            case Waiting w -> {
                while (retry()) {
                    yield new Running();       // producer inside the loop body
                }
                yield w;                       // give up: stay Waiting
            }
            case Done d -> d;                  // terminal self-loop
            case Failed f -> f;                // terminal self-loop
        };
    }

    static boolean retry() {
        return false;
    }
}
