package rootseed;

/**
 * NEGATIVE CONTROL for precedence: a root constant ({@code OFF}) and a driver field
 * ({@link Room}, seeded {@code new On()}) that DISAGREE. Rule 1 still requires
 * unanimity, so neither wins: the constant may not override a driver, and F31 does
 * not quietly promote the driver over the constant either. Initial state not
 * determined, with the disagreement reported.
 */
public sealed interface Lamp {

    Lamp OFF = new Off();

    Lamp next();

    record Off() implements Lamp {
        public Lamp next() {
            return new On();
        }
    }

    record On() implements Lamp {
        public Lamp next() {
            return new Off();
        }
    }
}
