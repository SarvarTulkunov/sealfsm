package examples.barefield;

/**
 * Centralized dispatch committed to a BARE field: {@code state = switch (state)
 * { ... }; return state;}. No {@code this.} qualifier anywhere, and the method
 * takes no hierarchy-typed parameter, so the signature-based recognizer
 * (hierarchy-in / hierarchy-out) sees nothing here.
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
