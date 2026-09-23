package enumbodies;

/**
 * A permitted enum whose constants carry bodies. The bodies override
 * {@link #watts()} only — they are the reason the enum is implicitly sealed, and
 * deliberately have nothing to do with the transition, so the fixture varies the
 * one thing under test.
 */
public enum Lit implements Lamp {
    DIM {
        @Override
        int watts() {
            return 10;
        }
    },
    BRIGHT {
        @Override
        int watts() {
            return 60;
        }
    };

    abstract int watts();

    @Override
    public Lamp press() {
        return switch (this) {
            case DIM -> BRIGHT;
            case BRIGHT -> new Off();
        };
    }
}
