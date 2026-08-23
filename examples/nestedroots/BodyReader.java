package nestedroots;

/**
 * The transition function, in the F2 mutation encoding: it commits the successor
 * through {@link BodyContext#setState} and returns nothing. That is what leaves
 * {@link Message} with no producer of its own to find.
 */
public final class BodyReader {

    public void advance(Body current, BodyContext ctx) {
        switch (current) {
            case Empty e -> ctx.setState(new Streaming(0L));
            case Streaming s -> ctx.setState(new Complete());
            case Complete c -> ctx.setState(new Empty());
        }
    }
}
