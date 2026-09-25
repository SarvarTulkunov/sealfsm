package carrierreject;

/**
 * F36 store-back (thesis Decision 4): unwraps each {@link Move} and installs its
 * state, which is what makes {@link Table#step} a transition. Unseeded.
 */
final class Installers {
    private Cell cell;

    void step(int event) {
        cell = Table.step(cell, event).next();
    }
}
