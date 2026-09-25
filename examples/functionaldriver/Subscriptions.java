package functionaldriver;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * F7 at the FUNCTIONAL_CALLABLE locus, written so that the store-back is in the
 * model (F36, thesis Decision 4).
 *
 * <p>This is {@code examples/cancellation} with one change. The two anonymous
 * {@code BiFunction}s are applied by an in-model {@link #accumulate}, which
 * installs each result as the current state, instead of by
 * {@code AtomicReference.accumulateAndGet}. That library method does install
 * the result, but the tool never reads a JDK body (F11), so there the
 * installation is invisible and the hierarchy is a provisional candidate
 * ({@code LIMITATIONS.md} L2). Here it is visible, so the same two callables form
 * a machine. The difference between the fixtures is exactly the evidence
 * Decision 4 asks for.
 *
 * <p>Expected, as for cancellation before F36: 2 direct branches, 2 atomic
 * states, 6/6, edges labelled {@code add} and {@code subscribe} (two supplying
 * methods, so the name IS the input, F22), initial state the entry pseudo-state.
 */
public final class Subscriptions {

    sealed interface Subscription permits Pending, Cancelled {}

    record Pending(List<Runnable> onCancel) implements Subscription {}

    record Cancelled() implements Subscription {}

    private Subscription state;

    Subscriptions(Subscription start) {
        this.state = start;
    }

    /** Applies the callable and installs its result: the store-back. */
    private <A> void accumulate(A argument, BiFunction<Subscription, A, Subscription> step) {
        state = step.apply(state, argument);
    }

    void subscribe(Runnable callback) {
        accumulate(callback, new BiFunction<Subscription, Runnable, Subscription>() {
            @Override
            public Subscription apply(Subscription current, Runnable cb) {
                if (current instanceof Cancelled) {
                    cb.run();                       // action: void call, not a transition
                    return current;                 // Cancelled --subscribe--> Cancelled
                }
                List<Runnable> pending = new ArrayList<>();
                pending.add(cb);
                return new Pending(pending);        // (Pending | entry) --subscribe--> Pending
            }
        });
    }

    void add(Runnable onDone) {
        accumulate(onDone, new BiFunction<Subscription, Runnable, Subscription>() {
            @Override
            public Subscription apply(Subscription current, Runnable done) {
                if (current instanceof Cancelled) {
                    return current;                 // Cancelled --add--> Cancelled
                }
                done.run();
                return new Cancelled();             // (Pending | entry) --add--> Cancelled
            }
        });
    }
}
