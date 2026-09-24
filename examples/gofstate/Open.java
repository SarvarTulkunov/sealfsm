package gofstate;

public final class Open implements TicketState {
    @Override
    public boolean assign(Desk desk) {
        desk.state = new Assigned();
        return true;
    }

    @Override
    public void close(Desk desk) {
        throw new IllegalStateException("assign first");
    }

    @Override
    public void reopen(Desk desk) {
        throw new IllegalStateException("already open");
    }
}
