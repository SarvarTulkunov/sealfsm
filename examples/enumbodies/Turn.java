package enumbodies;

/** A permitted enum whose transitions live in the constant bodies. */
public enum Turn implements Knob {
    LEFT {
        @Override
        public Knob next() {
            return RIGHT;
        }
    },
    RIGHT {
        @Override
        public Knob next() {
            return new Rest();
        }
    },
    CENTER;

    /** Inherited by CENTER only: LEFT and RIGHT override it. */
    @Override
    public Knob next() {
        return this;
    }
}
