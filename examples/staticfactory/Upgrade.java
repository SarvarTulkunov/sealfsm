package staticfactory;

import java.util.List;
import java.util.Optional;

/**
 * The shape the finding is about, taken from Apache Kafka's
 * {@code org.apache.kafka.raft.internals.KRaftVersionUpgrade} and rewritten rather
 * than copied: a sum type whose only method returning the hierarchy type is a
 * {@code static} factory on the root.
 *
 * <p>A per-state transition method is one whose body runs <em>in</em> a state —
 * the receiver's dynamic type selects it, which is what makes its declaring class
 * an exact source state. A static method has no receiver. {@link #empty()} runs in
 * no state at all, so reading it as a transition sources an edge at the root
 * interface, which is not a state, and publishes the result as a clean {@code 1/1}.
 *
 * <p>Must be REJECTED. {@link #toVoters()} type-tests {@code this} into an
 * {@code Optional} — a fold, not a commit — so the hierarchy may appear on the
 * candidate channel, never as a machine.
 */
public sealed interface Upgrade {

    record Empty() implements Upgrade {
    }

    record Version(int level) implements Upgrade {
    }

    record Voters(List<Integer> ids) implements Upgrade {
    }

    Upgrade EMPTY = new Empty();

    static Upgrade empty() {
        return EMPTY;
    }

    default Optional<Voters> toVoters() {
        if (this instanceof Voters) {
            return Optional.of((Voters) this);
        } else {
            return Optional.empty();
        }
    }
}
