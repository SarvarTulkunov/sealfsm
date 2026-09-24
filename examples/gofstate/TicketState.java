package gofstate;

/**
 * F33 FIXTURE, positive 2: the same pattern with the other two spellings, held
 * apart from positive 1. The interface methods are abstract, so every state
 * spells each rejection as its own throwing override. The successor is installed
 * by writing the context's field directly ({@code desk.state = ...}) rather than
 * through a mutator. {@code Assigned.close} commits only when the ticket is
 * resolved and throws otherwise, so its edge carries that guard.
 * {@code Open.assign} returns a {@code boolean}, which is not a successor.
 *
 * <p>Expected: 3 states, 3/3, FIELD_MUTATION, {@code Closed} terminal.
 */
public sealed interface TicketState permits Open, Assigned, Closed {
    boolean assign(Desk desk);

    void close(Desk desk);

    void reopen(Desk desk);
}
