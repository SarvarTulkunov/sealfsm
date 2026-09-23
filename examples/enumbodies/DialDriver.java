package enumbodies;

/** The transition function, hosted outside the hierarchy. */
public final class DialDriver {

    private DialDriver() {
    }

    public static Dial next(Dial d) {
        return switch (d) {
            case Idle i -> Level.LOW;
            case Level.LOW -> Level.HIGH;
            case Level.HIGH -> new Idle();
        };
    }
}
