package tcp;

/**
 * Input alphabet of the FSM. Sealed so an analyzer can enumerate every event
 * shape the transition guards may test: application calls ({@link UserCall}),
 * incoming segments ({@link SegmentArrival}), and timers ({@link Timeout}).
 */
public sealed interface Event permits UserCall, SegmentArrival, Timeout {
}
