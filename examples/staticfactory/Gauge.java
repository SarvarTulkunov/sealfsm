package staticfactory;

/**
 * NEGATIVE CONTROL for the exclusion, and the reason it cannot be a plain drop: a
 * static method declared ON the hierarchy can still be a transition function. It
 * is simply not a <em>per-state</em> one. {@link #next} is the centralized
 * transition function {@code examples/door} hosts in a separate class, moved onto
 * the root interface — an ordinary place for it once the interface is sealed.
 *
 * <p>The dispatch is a {@code switch} <em>statement</em> whose arms return, so
 * {@code DispatchCommitDetector} (which inspects the switch's parent) cannot see
 * it, and the signature-based centralized recognizer is the only one that can.
 * That recognizer used to skip every method declared inside the hierarchy on the
 * grounds that the distributed one had counted it. Once statics stop being
 * distributed that reasoning no longer covers them, so they are offered to the
 * centralized recognizer instead — which asks for the discrimination
 * {@link Upgrade#empty()} does not have. Drop the static methods without
 * re-offering them and this machine is lost outright.
 *
 * <p>Low --UP--> Mid --UP--> High --DOWN--> Mid --DOWN--> Low, each other input
 * leaving the state where it is. 3 states, 6 edges, all resolved.
 */
public sealed interface Gauge {

    record Low() implements Gauge {
    }

    record Mid() implements Gauge {
    }

    record High() implements Gauge {
    }

    static Gauge next(Gauge current, Tick tick) {
        switch (current) {
            case Low l:
                return tick == Tick.UP ? new Mid() : l;
            case Mid m:
                return tick == Tick.UP ? new High() : new Low();
            case High h:
                return tick == Tick.DOWN ? new Mid() : h;
        }
    }
}
