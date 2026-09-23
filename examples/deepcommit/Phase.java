package deepcommit;

/**
 * F32 FIXTURE: two discriminations of one hierarchy on one class ({@link Kiln}),
 * which fail to prove a commit for OPPOSITE reasons. The candidate must say which
 * is which. This is the shape found on Apache Kafka's KRaft
 * ({@code QuorumState.maybeLeaderState} folds into {@code Optional};
 * {@code KafkaRaftClient.maybeTransitionForward} is a real transition dispatch
 * whose commit lies five calls away). Before F32 both were reported under one
 * sentence, "no branch installs a hierarchy value … the exhaustive-fold guard",
 * which is false of the second.
 */
public sealed interface Phase {

    record Cold() implements Phase {
    }

    record Warm() implements Phase {
    }

    record Hot() implements Phase {
    }
}
