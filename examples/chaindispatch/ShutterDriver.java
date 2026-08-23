package chaindispatch;

/**
 * A driver, present so the machine has a recoverable initial state: the seeded
 * field {@code new Open()} is what initial-state rule 1 reads. It holds no
 * dispatch of its own — the assignment's right-hand side is a call, not a
 * discrimination — so it adds a seed and nothing else.
 */
public final class ShutterDriver {

    private Shutter position = new Open();

    public Shutter apply(Command command) {
        position = ShutterLogic.next(position, command);
        return position;
    }
}
