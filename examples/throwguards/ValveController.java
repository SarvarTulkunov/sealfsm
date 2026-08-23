package throwguards;

/**
 * Centralized dispatch whose arms leave by <em>throwing</em> as often as by
 * producing a successor — the ordinary shape of a machine that rejects an
 * input rather than ignoring it (finding F14).
 *
 * <p>Every arm below is the same statement pair: a rejection test, then a
 * producer. Whether the producer's guard may carry the negated test depends
 * entirely on whether the rejection branch can complete normally, and the
 * five arms are deliberately not interchangeable:
 *
 * <ul>
 *   <li><b>{@code Charged}</b> — a bare {@code throw}. The producer below is
 *       reached only when the test failed, so its guard must carry the
 *       negation.</li>
 *   <li><b>{@code Venting}</b> — a <em>block</em> ending in a throw. The
 *       block completes abruptly because its last statement does; the
 *       bookkeeping call before it changes nothing.</li>
 *   <li><b>{@code Faulted}</b> — an exhaustive {@code switch} statement,
 *       every arm of which throws. No arm and no {@code break} can carry
 *       control past it, so it cannot complete normally either.</li>
 *   <li><b>{@code Bleeding}</b> — <b>negative control</b>. The branch
 *       <em>contains</em> a throw but takes it only conditionally, so it
 *       completes normally and the producer below is unconditional. Reading
 *       "contains a throw" as "always throws" fabricates a guard: the edge
 *       would claim not to fire on {@code OPEN}, which is a stronger claim
 *       than the source makes and the direction the soundness invariant
 *       forbids.</li>
 *   <li><b>{@code Latched}</b> — <b>negative control</b>. An exhaustive
 *       switch whose default arm leaves by {@code break}: control resumes
 *       immediately after the switch, so the producer below runs on every
 *       command. Here the switch is a bare sibling rather than an {@code if}
 *       branch, so misjudging it does not merely mislabel a guard — the
 *       producer is written off as unreachable and the EDGE IS LOST.</li>
 *   <li><b>{@code Purging}</b> — <b>negative control</b>. The same shape
 *       with the {@code break} taken out: a trailing {@code default:} label
 *       carrying no statements at all. An empty arm is answered by the group
 *       below it, which is exactly why the LAST one cannot be — there is
 *       nothing below it, so control falls out of the switch.</li>
 * </ul>
 */
public final class ValveController {

    private Valve valve = new Charged();

    private int pressure = 0;

    public Valve valve() {
        return valve;
    }

    public void accept(Command cmd) {
        valve = transition(valve, cmd);
    }

    public Valve transition(Valve current, Command cmd) {
        return switch (current) {
            case Charged c -> {
                if (cmd == Command.RESET) {
                    throw new IllegalStateException("cannot reset a charged valve");
                }
                yield new Venting();
            }
            case Venting v -> {
                if (cmd == Command.PURGE) {
                    audit(cmd);
                    throw new IllegalStateException("cannot purge while venting");
                }
                yield new Charged();
            }
            case Faulted f -> {
                if (cmd != Command.RESET) {
                    switch (cmd) {
                        case OPEN -> throw new IllegalStateException("faulted: open refused");
                        case CLOSE -> throw new IllegalStateException("faulted: close refused");
                        default -> throw new IllegalStateException("faulted: input refused");
                    }
                }
                yield new Charged();
            }
            case Bleeding b -> {
                if (cmd == Command.OPEN) {
                    if (pressure > 100) {
                        throw new IllegalStateException("overpressure while bleeding");
                    }
                    audit(cmd);
                }
                yield new Faulted();
            }
            case Latched l -> {
                switch (cmd) {
                    case OPEN:
                        throw new IllegalStateException("cannot open a latched valve");
                    default:
                        break;
                }
                yield new Venting();
            }
            case Purging p -> {
                switch (cmd) {
                    case OPEN:
                        throw new IllegalStateException("cannot open while purging");
                    default:
                }
                yield new Charged();
            }
        };
    }

    private void audit(Command cmd) {
        pressure = pressure + 1;
    }
}
