package throwcarrier;

import java.util.Optional;

/** HELD — brake set. The only way out is back to Parked. */
public final class Held implements Hoist {

    @Override
    public Optional<Hoist> on(Lever lever) {
        return Optional.of(new Parked());
    }
}
