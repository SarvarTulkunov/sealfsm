package examples.eventalphabet;

/**
 * Sealed root for the event-alphabet micro-benchmark (finding F4). The transition
 * function dispatches on a <em>second</em> sealed parameter — the {@link Event}
 * type — so Σ is recoverable exactly and completely from that type's {@code
 * permits} clause, and each nested switch-over-event arm labels its edge with the
 * matched event.
 */
public sealed interface Player permits Stopped, Playing, Paused {}
