package rootseed;

/** A driver field that disagrees with {@link Lamp#OFF}. */
public final class Room {

    private Lamp lamp = new Lamp.On();

    public void flick() {
        lamp = lamp.next();
    }
}
