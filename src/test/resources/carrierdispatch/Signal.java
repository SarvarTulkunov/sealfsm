package carrierdispatch;

/**
 * The hierarchy under test. Nothing about it is unusual — the fixture varies
 * only what the DISPATCH returns, which is the whole point: the states are
 * ordinary and the combination was unreachable anyway.
 */
public sealed interface Signal permits Idle, Live, Done {
}
