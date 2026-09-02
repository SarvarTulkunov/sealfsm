package eventmajor;

/**
 * A decoder: closed selector, two committing arms, and <b>no hierarchy value in</b>.
 *
 * <p>The class deliberately declares no {@code Shade} field and the method no
 * {@code Shade} parameter. {@code result} is a local, which is the successor under
 * construction rather than the current state, and is not counted — the same
 * distinction rule 3 of the initial-state heuristic already draws between a field
 * and a local inside a state class.
 */
public final class ShadeFactory {

    public static Shade of(FrameType frameType) {
        Shade result = new Pale();
        switch (frameType) {
            case DATA -> result = new Pale();
            case GOAWAY -> result = new Deep();
            default -> result = new Pale();
        }
        return result;
    }
}
