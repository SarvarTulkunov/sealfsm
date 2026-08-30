package examples.barefield;

/**
 * Centralized dispatch committed to a BARE field: {@code state = switch (state)
 * { ... }; return state;}. No {@code this.} qualifier anywhere, and the method
 * takes no hierarchy-typed parameter. That once put it outside the signature-based
 * recognizer entirely (hierarchy-in / hierarchy-out); since F19 that recognizer asks
 * whether the state is DISCRIMINATED rather than whether it is a parameter, so this
 * driver is recognised — and the parameter's remaining job is to say how much of the
 * body to walk. It has none, so the discrimination's own producer walks it, which is
 * what keeps the trailing {@code return state;} below from being read as a successor
 * and what keeps this machine FIELD_MUTATION rather than VALUE_RETURN.
 *
 * <p>{@code Fired} is terminal by construction: its arm rejects every signal, so
 * it has zero outbound edges. The {@code default -> throw} arms are likewise
 * explicit rejections and must contribute no edge at all — modelling them as
 * self-loops would invent transitions the protocol forbids.
 */
public final class LatchDriver {

    private Latch state = new Idle();

    public Latch state() {
        return state;
    }

    public Latch accept(Signal signal) {
        state = switch (state) {
            case Idle() -> switch (signal) {
                case ARM -> new Armed();
                case RESET -> new Idle();
                default -> throw reject(signal);
            };
            case Armed() -> switch (signal) {
                case TRIGGER -> new Fired();
                case RESET -> new Idle();
                default -> throw reject(signal);
            };
            case Fired() -> throw reject(signal);
        };
        return state;
    }

    private IllegalStateException reject(Signal signal) {
        return new IllegalStateException("signal " + signal + " is illegal in " + state);
    }
}
