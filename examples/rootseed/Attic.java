package rootseed;

/** Starts in {@code Spinning}, through a factory rule 1 cannot see into. */
public final class Attic {

    private Fan fan = Fan.running();

    public void toggle() {
        fan = fan.next();
    }
}
