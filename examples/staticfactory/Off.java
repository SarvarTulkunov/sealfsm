package staticfactory;

public final class Off implements Lamp {

    /** A factory that happens to live on the state it builds; runs in no state. */
    public static Off create() {
        return new Off();
    }

    @Override
    public Lamp toggle() {
        return new On();
    }
}
