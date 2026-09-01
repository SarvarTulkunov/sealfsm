package unreadablecallee;

/**
 * The dispatch: exhaustive over {@code Shutter}, every arm a bare call whose
 * value is discarded — and the callee's body is not in the source set.
 *
 * <p>The driver holds a root-typed state field, seeded, exactly as
 * {@code voidcommit}'s does, so the two are not separated by anything but the
 * readability of the callee.
 */
public final class ShutterDriver {

    private final ShutterSink sink;
    private Shutter state = new Open();

    public ShutterDriver(ShutterSink sink) {
        this.sink = sink;
    }

    public void step(Command command) {
        switch (state) {
            case Open o -> sink.install(o, command);
            case Closing c -> sink.install(c, command);
            case Shut s -> sink.install(s, command);
        }
    }

    public Shutter state() {
        return state;
    }
}
