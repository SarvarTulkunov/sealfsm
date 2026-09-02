package eventmajor;

/**
 * The shape the finding is about, in <b>both</b> spellings, held on ONE class so
 * that no difference of file or context can stand in for the difference being
 * tested.
 *
 * <p>{@link #consumeFrame} commits <em>directly</em>: the arm assigns the
 * root-typed field, which is the commit the analysis can see at the dispatch. This
 * is Apache HttpClient 5's {@code AbstractHttp1StreamDuplexer.close(closeMode)}
 * written over a sealed hierarchy instead of an enum.
 *
 * <p>{@link #consumeCommand} commits <em>one call away</em>, through
 * {@link #install}, whose value Java discards (JLS §14.8) — so nothing at the
 * dispatch could prove or disprove a commit and only the k = 1 probe can answer.
 * It is Apache's {@code AbstractH2StreamMultiplexer.consumeFrame} shape.
 *
 * <p>{@code install} deliberately takes TWO parameters and commits neither of them,
 * so it is <b>not</b> a recognised mutator — a mutator commits what it was handed,
 * and this does not. That keeps the second host on the probe's path rather than on
 * the {@code MUTATOR_ARGUMENT} path, exactly as {@code examples/voidcommit} does,
 * which is what makes the two spellings independent evidence rather than one.
 *
 * <p>The successor in the second host is an arithmetic index into an array, and
 * that is not obfuscation for its own sake: this fixture asserts that <b>no</b>
 * relation is claimed, and a successor the resolver could reach would leave that
 * assertion testing nothing.
 */
public final class LinkMultiplexer {

    private static final Link[] WHEEL = { new Ready(), new Active(), new Draining(), new Closed() };

    private Link connState = new Ready();
    private int frames;

    /** Σ-major, committing directly: the switch is over the EVENT, the arm installs a STATE. */
    public void consumeFrame(FrameType frameType) {
        frames++;
        switch (frameType) {
            case DATA -> connState = new Active();
            case SETTINGS -> connState = new Active();
            case PING -> connState = new Draining();
            case GOAWAY -> connState = new Closed();
        }
    }

    /** Σ-major, committing one call away — the cell only the k = 1 probe can reach. */
    public void consumeCommand(FrameType frameType) {
        switch (frameType) {
            case DATA -> install(connState, frameType);
            case SETTINGS -> install(connState, frameType);
            case PING -> install(connState, frameType);
            case GOAWAY -> install(connState, frameType);
        }
    }

    private void install(Link current, FrameType frameType) {
        this.connState = WHEEL[(frames + frameType.ordinal() + current.hashCode()) % WHEEL.length];
    }

    public Link connState() {
        return connState;
    }
}
