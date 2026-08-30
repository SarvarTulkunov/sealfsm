package throwcarrier;

import java.util.Optional;

/** PARKED — stowed at the bottom. Edges: RAISE to Raising, otherwise stay. */
public final class Parked implements Hoist {

    @Override
    public Optional<Hoist> on(Lever lever) {
        if (lever == Lever.RAISE) return Optional.of(new Raising());
        return Optional.of(this);
    }
}
