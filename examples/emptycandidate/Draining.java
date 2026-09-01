package emptycandidate;

/**
 * A permitted ENUM. Its constants are closed in exactly the way a {@code permits}
 * clause is, so each becomes a child state — which is why the candidate's state
 * count is seven and not three.
 */
public enum Draining implements Channel { FLUSHING, PARKED }
