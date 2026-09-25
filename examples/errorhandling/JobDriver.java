package examples.errorhandling;

/**
 * F36 driver (thesis Decision 4): stores each successor back as the current
 * state, which is what makes {@link JobMachine#transition} a transition rather
 * than a conversion. Unseeded on purpose, so the initial-state heuristics see
 * nothing new.
 */
final class JobDriver {
    private Job job;

    JobDriver(Job start) {
        this.job = start;
    }

    void step() {
        job = JobMachine.transition(job);
    }
}
