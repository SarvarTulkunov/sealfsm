package rootseed;

/**
 * NEGATIVE CONTROL for the label: the same root constant as {@link Valve}, and a
 * driver field ({@link Yard}) seeded {@code new Closed()} as well. The two agree,
 * and a driver vouches for the answer, so it is NOT reported as weaker evidence.
 * Without this control, "label every answer a root constant touched" would pass
 * the {@code Valve} assertion just as well.
 */
public sealed interface Gate {

    Gate CLOSED = new Closed();

    Gate next();

    record Closed() implements Gate {
        public Gate next() {
            return new Ajar();
        }
    }

    record Ajar() implements Gate {
        public Gate next() {
            return new Closed();
        }
    }
}
