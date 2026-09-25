package statelevels;

/**
 * An OPEN branch. {@code Ajar} is {@code non-sealed}, so the permits clause stops
 * at it and the type system does not close it. {@code WideAjar} extends it, is
 * not listed anywhere, and is not a state of its own. The exact claim is that
 * {@code Hatch} has two type branches. It is not a claim about every class a
 * hatch may have at run time, and the extension is reported, not silently merged.
 */
public sealed interface Hatch permits Shut, Ajar {
}

final class Shut implements Hatch {
}

non-sealed class Ajar implements Hatch {
}

class WideAjar extends Ajar {
}

final class HatchMachine {
    static Hatch next(Hatch h) {
        return switch (h) {
            case Shut s -> new Ajar();
            case Ajar a -> new Shut();
        };
    }
}

/** The store-back (F36), unseeded. */
final class HatchDriver {
    private Hatch hatch;

    HatchDriver(Hatch start) {
        this.hatch = start;
    }

    void swing() {
        hatch = HatchMachine.next(hatch);
    }
}
