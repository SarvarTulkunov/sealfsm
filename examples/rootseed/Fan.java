package rootseed;

/**
 * The COST of trusting a root constant, pinned rather than hidden. The driver
 * ({@link Attic}) starts in {@code Spinning}, through a static factory, and rule 1
 * reads only {@code new} expressions, so it never sees that start. The one seed it
 * does see is the root constant {@code IDLE}, and it answers {@code Idle}: a wrong
 * initial state.
 *
 * <p>This is why F31 labels a constant-only answer as weaker evidence instead of
 * presenting it as a state field. It is also a standing probe: if rule 1 ever
 * learns to follow a factory into the driver's field, this answer becomes
 * {@code Spinning} (or a reported disagreement), and the test pinning it must fail
 * loudly and be updated.
 */
public sealed interface Fan {

    Fan IDLE = new Idle();

    Fan next();

    static Fan running() {
        return new Spinning();
    }

    record Idle() implements Fan {
        public Fan next() {
            return new Spinning();
        }
    }

    record Spinning() implements Fan {
        public Fan next() {
            return new Idle();
        }
    }
}
