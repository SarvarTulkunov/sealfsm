package sidecaller;

/**
 * F39 FIXTURE, the dispatcher half. The same machine shape as {@link Barrier},
 * but the only store-back is a command dispatcher that IS installed:
 * {@code state = dispatch(state, cmd)}. Its site is walked; its calls are virtual
 * with three bodies each, so the fold cannot enter them, and they must not be
 * written off as "folded into the caller" either.
 */
public sealed interface Kiosk permits Idle, Serving, Shut {

    Kiosk onOrder();

    Kiosk onServe();

    Kiosk onClose();
}

final class Idle implements Kiosk {
    @Override public Kiosk onOrder() { return new Serving(); }
    @Override public Kiosk onServe() { return this; }
    @Override public Kiosk onClose() { return new Shut(); }
}

final class Serving implements Kiosk {
    @Override public Kiosk onOrder() { return this; }
    @Override public Kiosk onServe() { return new Idle(); }
    @Override public Kiosk onClose() { return new Shut(); }
}

final class Shut implements Kiosk {
    @Override public Kiosk onOrder() { throw new IllegalStateException("shut"); }
    @Override public Kiosk onServe() { throw new IllegalStateException("shut"); }
    @Override public Kiosk onClose() { return this; }
}

/** A REPL: the command names the per-state method, and the result is installed. */
final class KioskConsole {
    private Kiosk state;

    void run(String cmd) {
        state = dispatch(state, cmd);
    }

    static Kiosk dispatch(Kiosk s, String cmd) {
        switch (cmd) {
            case "order":
                return s.onOrder();
            case "serve":
                return s.onServe();
            case "close":
                return s.onClose();
            default:
                throw new IllegalArgumentException(cmd);
        }
    }
}
