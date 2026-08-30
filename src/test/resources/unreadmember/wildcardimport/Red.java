package wildcardimport;

import ext.*;

public final class Red implements Light {
    @Override
    public Light next() {
        return new Amber();
    }
}
