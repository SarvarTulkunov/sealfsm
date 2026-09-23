package enumbodies;

/** OPEN — a composite state with four members. */
public sealed interface Open extends Valve permits Open.Half, Open.Full, Open.Leaky, Open.Stuck {

    @Override
    default Valve toggle() {
        return switch (this) {
            case Half h -> new Full();
            case Full f -> new Shut();
            default -> this;
        };
    }

    final class Half implements Open {
    }

    final class Full implements Open {
    }

    final class Leaky implements Open {
    }

    /** Overrides the default, so none of its arms run in this state. */
    final class Stuck implements Open {
        @Override
        public Valve toggle() {
            return new Shut();
        }
    }
}
