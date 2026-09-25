package typedhandler;

/**
 * Two controls on the typed-parameter source rule, on one class.
 *
 * <p>LABELS: {@code open(Shut)} and {@code jam(Shut)} leave the same state, so
 * the method IS the input. With three names across the handlers they discriminate
 * (F22) and every edge carries its method name; unlabelled, Shut would read as
 * nondeterministic.
 *
 * <p>AMBIGUITY: {@code merge(Open, Shut)} takes TWO states. Which one is current
 * is not in the signature, so no source may be claimed for it: its edge stays
 * sourced at {@code <unknown>}, never at Open or Shut.
 *
 * <p>{@link HatchPanel} is the store-back F36 requires (thesis Decision 4).
 * Without it these handlers are indistinguishable from {@link Length}'s
 * converters, and the controls above would have no machine to control.
 */
public sealed interface Hatch permits Hatch.Open, Hatch.Shut, Hatch.Jammed {
    record Open() implements Hatch {}
    record Shut() implements Hatch {}
    record Jammed() implements Hatch {}
}

final class HatchControl {
    Hatch close(Hatch.Open open) { return new Hatch.Shut(); }
    Hatch open(Hatch.Shut shut) { return new Hatch.Open(); }
    Hatch jam(Hatch.Shut shut) { return new Hatch.Jammed(); }
    Hatch merge(Hatch.Open a, Hatch.Shut b) { return new Hatch.Jammed(); }
}

/** Installs every handler's result as the current state. Unseeded on purpose. */
final class HatchPanel {
    private final HatchControl control = new HatchControl();
    private Hatch hatch;

    HatchPanel(Hatch start) {
        this.hatch = start;
    }

    void closeFrom(Hatch.Open open) {
        hatch = control.close(open);
    }

    void openFrom(Hatch.Shut shut) {
        hatch = control.open(shut);
    }

    void jamFrom(Hatch.Shut shut) {
        hatch = control.jam(shut);
    }

    void mergeOf(Hatch.Open a, Hatch.Shut b) {
        hatch = control.merge(a, b);
    }
}
