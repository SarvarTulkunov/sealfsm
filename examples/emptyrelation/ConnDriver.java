package emptyrelation;

import java.util.Map;

/**
 * Σ-major dispatch whose arms delegate to helpers that ARE recognised transition
 * functions by signature — and whose bodies the walk cannot reach.
 *
 * <p>The helpers are what make this a machine rather than a candidate: each takes
 * and returns {@code Conn}, so the hierarchy is accepted, and the Σ-major
 * recognizer (which only feeds the candidate channel) never runs. What is left is
 * an accepted machine with nothing to attribute — the exact combination the tier
 * table had no position for.
 */
public final class ConnDriver {

    private final Map<String, Conn> table = Map.of();
    private final Object lock = new Object();

    private Conn state = new Down();

    public void step(Ev eventName) {
        switch (eventName) {
            case ACTIVE -> state = extractState1(state, eventName);
            case DEACTIVE -> state = extractState2(state, eventName);
            case FAIL -> state = extractState3(state, eventName);
        }
    }

    private Conn extractState1(Conn current, Ev eventName) {
        synchronized (lock) {
            return table.get("1");
        }
    }

    private Conn extractState2(Conn current, Ev eventName) {
        synchronized (lock) {
            return table.get("2");
        }
    }

    private Conn extractState3(Conn current, Ev eventName) {
        synchronized (lock) {
            return table.get("3");
        }
    }

    public Conn state() {
        return state;
    }
}
