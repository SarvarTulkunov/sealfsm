package gofstate;

/**
 * F33 NEGATIVE CONTROL for "some production names another state". Two members
 * install a hierarchy value into a holder outside the hierarchy through a
 * structural mutator, which meets every other requirement. But each installs
 * {@code this}, so nothing moves: a message hierarchy recording the last one
 * sent is not a state machine. Must yield no machine.
 */
public sealed interface Message permits Ping, Pong {
    void send(Bus bus);
}
