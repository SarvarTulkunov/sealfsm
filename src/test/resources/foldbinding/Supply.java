package foldbinding;

/**
 * A producer whose declaration carries no body. The fold must decline here for
 * the reason it always has — there is nothing to summarise — and the caller must
 * record the gap rather than reach for the argument it happens to be holding.
 */
public interface Supply {
    Flow supply(Flow s);
}
