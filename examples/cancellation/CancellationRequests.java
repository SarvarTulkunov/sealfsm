package examples.cancellation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/**
 * A cancellation registry whose transitions live in anonymous {@link BiFunction}s
 * handed to an atomic accumulator, not in a named switch-based transition method.
 *
 * <p>This is the finding-F7 witness. It exercises, together:
 * <ul>
 *   <li><b>Transition-callable discovery by signature.</b> The transition
 *       functions are anonymous-class {@code apply} methods — hierarchy-typed
 *       input, hierarchy-typed result — discovered regardless of the enclosing
 *       {@code accumulateAndGet} API.</li>
 *   <li><b>{@code instanceof} from-state attribution.</b> Each callable dispatches
 *       on the root-typed {@code current} selector with {@code if (current
 *       instanceof Cancelled)} rather than a {@code switch}.</li>
 *   <li><b>Residual-as-entry.</b> The path where {@code current} is neither
 *       permitted subtype (machine entry) produces the initial-state edges.</li>
 *   <li><b>Self-loop via selector return.</b> {@code return current} on an
 *       unmodified root-typed selector is a self-loop to the matched state.</li>
 *   <li><b>Action skipping.</b> Callback invocations and list mutations are
 *       side effects, never transitions.</li>
 *   <li><b>Enclosing-method event labels.</b> The anonymous callables are named
 *       by the methods that supply them: {@code add} and {@code subscribe}.</li>
 * </ul>
 */
public final class CancellationRequests {

    /** The two-state machine, enumerated exactly from the permits clause. */
    sealed interface CancellationState permits Pending, Cancelled {}

    /** Not yet cancelled; carries the callbacks to run when cancellation happens. */
    record Pending(List<Runnable> onCancel) implements CancellationState {}

    /** Terminal: the request has been cancelled. */
    record Cancelled() implements CancellationState {}

    private final AtomicReference<CancellationState> state =
            new AtomicReference<>(new Pending(new ArrayList<>()));

    /**
     * Register a cancellation callback. If cancellation already happened, run the
     * callback now (an action) and stay cancelled; otherwise remain pending.
     */
    void subscribe(Runnable callback) {
        state.accumulateAndGet(callback, new BiFunction<CancellationState, Runnable, CancellationState>() {
            @Override
            public CancellationState apply(CancellationState current, Runnable cb) {
                if (current instanceof Cancelled) {
                    cb.run();                       // action: void call — not a transition
                    return current;                 // Cancelled --subscribe--> Cancelled (self-loop)
                }
                List<Runnable> pending = new ArrayList<>();  // action: local, non-state
                pending.add(cb);                             // action: collection mutation
                return new Pending(pending);                 // (Pending | entry) --subscribe--> Pending
            }
        });
    }

    /**
     * Request cancellation. If already cancelled, stay; otherwise run the pending
     * completion callback (an action) and move to cancelled.
     */
    void add(Runnable onDone) {
        state.accumulateAndGet(onDone, new BiFunction<CancellationState, Runnable, CancellationState>() {
            @Override
            public CancellationState apply(CancellationState current, Runnable done) {
                if (current instanceof Cancelled) {
                    return current;                 // Cancelled --add--> Cancelled (self-loop)
                }
                done.run();                         // action: void call — not a transition
                return new Cancelled();             // (Pending | entry) --add--> Cancelled
            }
        });
    }
}
