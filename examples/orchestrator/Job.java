package orchestrator;

/**
 * NEGATIVE CONTROL for "an abstract handler with ONE implementation is the body
 * that runs": the same driver shape as {@link OrderOrchestrator}, but
 * {@link JobRunner#advance} has TWO implementations that disagree. Which one runs
 * is not decidable from the driver, so folding either would publish its answer as
 * the machine's. Expected: 3 states, each driver arm an UNRESOLVED edge with a
 * known source, Done terminal (the arm that returns without re-entering halts).
 * Each implementation is itself a typed handler walked on its own (F39), so the
 * relation also holds the union of both: 3/5.
 */
public sealed interface Job {
    record Queued(int id) implements Job {}
    record Running(int id) implements Job {}
    record Done(int id) implements Job {}
}
