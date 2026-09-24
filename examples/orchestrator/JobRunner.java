package orchestrator;

public interface JobRunner {
    default Job run(Job job) {
        return switch (job) {
            case Job.Queued q -> run(advance(q));
            case Job.Running r -> run(advance(r));
            case Job.Done d -> d;
        };
    }

    Job advance(Job.Queued queued);

    Job advance(Job.Running running);
}

final class DirectRunner implements JobRunner {
    @Override public Job advance(Job.Queued queued) { return new Job.Done(queued.id()); }
    @Override public Job advance(Job.Running running) { return new Job.Done(running.id()); }
}

final class SteppedRunner implements JobRunner {
    @Override public Job advance(Job.Queued queued) { return new Job.Running(queued.id()); }
    @Override public Job advance(Job.Running running) { return new Job.Done(running.id()); }
}
