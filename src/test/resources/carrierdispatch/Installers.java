package carrierdispatch;

/**
 * F36 store-backs (thesis Decision 4): each carrier is unwrapped and its state
 * installed, which is what makes {@code route} and {@code flip} transitions rather
 * than conversions into a record. The two controls ({@code describe}, which folds
 * into a record with no state slot, and {@code fork}, whose two slots are
 * ambiguous) are deliberately not called. Unseeded.
 */
final class Installers {
    private Signal signal;
    private Latch latch;

    void route(int event) {
        signal = Router.route(signal, event).next();
    }

    void flip(int event) {
        latch = ChainRouter.flip(latch, event).next();
    }
}
