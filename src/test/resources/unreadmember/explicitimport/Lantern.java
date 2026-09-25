package explicitimport;

/**
 * F36 store-back (thesis Decision 4): installs {@code Light.next()}'s successor as
 * the current state, so the hierarchy is a machine whose edges this directory's
 * resolution test can inspect. Unseeded. Like the rest of the variant, it may
 * reference a type the model cannot read.
 */
final class Lantern {
    private Light light;

    void tick() {
        light = light.next();
    }
}
