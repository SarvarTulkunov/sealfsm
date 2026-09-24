package gofstate;

public final class Closed implements TicketState {
    @Override
    public boolean assign(Desk desk) {
        throw new IllegalStateException("closed");
    }

    @Override
    public void close(Desk desk) {
        throw new IllegalStateException("closed");
    }

    @Override
    public void reopen(Desk desk) {
        throw new IllegalStateException("closed tickets stay closed");
    }
}
