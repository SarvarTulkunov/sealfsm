package rootseed;

/**
 * F31, the Kafka shape ({@code KRaftVersionUpgrade EMPTY = new Empty();}): the only
 * {@code new} that seeds this machine is a constant declared on the sealed root.
 * The driver starts from the constant ({@link Pipe}), so {@code Shut} IS the
 * initial state — but the evidence is a shared value, not a driver's state field,
 * and the report must say so. Initial {@code Shut}, labelled as weaker evidence.
 */
public sealed interface Valve {

    Valve SHUT = new Shut();

    Valve next();

    record Shut() implements Valve {
        public Valve next() {
            return new Open();
        }
    }

    record Open() implements Valve {
        public Valve next() {
            return new Shut();
        }
    }
}
