package staticfactory;

public final class On implements Lamp {

    @Override
    public Lamp toggle() {
        return dimmed();
    }

    /** Reached only through {@link #toggle()}; its successor is folded there. */
    private static Lamp dimmed() {
        return new Off();
    }
}
