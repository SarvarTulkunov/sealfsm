package examples.traffic;

/**
 * Distributed (classic State pattern) encoding. The sealed root enumerates the
 * states; each permitted subtype carries its own {@code next()} transition.
 *
 * Expected extraction:
 *   states:       Red, Green, Yellow
 *   transitions:  Red --next--> Green, Green --next--> Yellow, Yellow --next--> Red
 *   initial:      Red   (from TrafficController.current = new Red())
 */
public sealed interface TrafficLight permits Red, Green, Yellow {
    TrafficLight next();
}
