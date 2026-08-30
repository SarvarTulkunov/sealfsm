package throwcarrier;

import java.util.Optional;

/** RAISING — under power. Edges: HOLD to Held, otherwise back to Parked. */
public final class Raising implements Hoist {

    @Override
    public Optional<Hoist> on(Lever lever) {
        if (lever == Lever.HOLD) return Optional.of(new Held());
        return Optional.of(new Parked());
    }
}
