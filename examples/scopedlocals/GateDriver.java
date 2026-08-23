package scopedlocals;

/**
 * Centralized dispatch whose three arms each declare a local named
 * {@code next} — the ordinary way a large per-arm switch is written, and the
 * shape that makes "reassigned?" a question about variable IDENTITY rather
 * than about the spelling of a name (finding F13).
 *
 * <p>The three arms are deliberately not interchangeable:
 *
 * <ul>
 *   <li><b>{@code Shut}</b> — a single-assignment local. Its value is fixed by
 *       its initializer, so the resolver may read it directly and report the
 *       successor form as a LOCAL_VARIABLE.</li>
 *   <li><b>{@code Ajar}</b> — the <b>negative control</b>. Here {@code next}
 *       really is reassigned, and it is ROOT-typed: treating it as
 *       single-assignment would resolve its initializer {@code current} to a
 *       confident, unguarded self-loop and lose the {@code new Wedged()}
 *       target altogether. That is finding F1, and narrowing the reassignment
 *       test must not reintroduce it.</li>
 *   <li><b>{@code Wedged}</b> — a single-assignment local read from inside a
 *       {@code try}. The reaching-definitions fallback does not model
 *       exceptional flow and bails there, so misjudging this local does not
 *       merely mislabel its successor form: the edge is LOST to {@code -> ?}.
 *       This is the arm that turns the scoping bug into a recall gap.</li>
 * </ul>
 *
 * <p>Every arm is a sibling block of the others, so no arm shadows another —
 * these are three distinct variables that share a name, which is precisely
 * what a name-keyed scan cannot tell apart.
 */
public final class GateDriver {

    private Gate gate = new Shut();

    public Gate gate() {
        return gate;
    }

    public void step(Pulse pulse) {
        gate = transition(gate, pulse);
    }

    public static Gate transition(Gate current, Pulse pulse) {
        return switch (current) {
            case Shut s -> {
                Gate next = new Ajar();
                yield next;
            }
            case Ajar a -> {
                Gate next = current;
                if (pulse == Pulse.JAM) {
                    next = new Wedged();
                }
                yield next;
            }
            case Wedged w -> {
                Gate next = new Shut();
                try {
                    audit(pulse);
                    yield next;
                } catch (RuntimeException e) {
                    yield new Ajar();
                }
            }
        };
    }

    private static void audit(Pulse pulse) {
        if (pulse == null) {
            throw new IllegalArgumentException("null pulse");
        }
    }
}
