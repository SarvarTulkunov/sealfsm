package gofstate;

/** The context for {@link TicketState}. Its states write {@code state} directly. */
public final class Desk {
    TicketState state = new Open();
    boolean resolved;

    boolean resolved() {
        return resolved;
    }
}
