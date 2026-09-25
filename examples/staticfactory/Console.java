package staticfactory;

/**
 * F36 driver (thesis Decision 4): installs each machine's successor as its
 * current state. For {@code Gauge} that is the static transition function
 * declared on the root, and for {@code Lamp} the per-state {@code toggle()}.
 * The factories ({@code Lamp.initial()}, {@code Off.create()}) are still called by
 * no one, and still transition nothing (F30). Unseeded on purpose.
 */
final class Console {
    private Gauge gauge;
    private Lamp lamp;

    Console(Gauge gauge, Lamp lamp) {
        this.gauge = gauge;
        this.lamp = lamp;
    }

    void tick(Tick tick) {
        gauge = Gauge.next(gauge, tick);
    }

    void flip() {
        lamp = lamp.toggle();
    }
}
