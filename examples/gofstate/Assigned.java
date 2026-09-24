package gofstate;

public final class Assigned implements TicketState {
    @Override
    public boolean assign(Desk desk) {
        throw new IllegalStateException("already assigned");
    }

    @Override
    public void close(Desk desk) {
        if (desk.resolved()) {
            desk.state = new Closed();
        } else {
            throw new IllegalStateException("not resolved");
        }
    }

    @Override
    public void reopen(Desk desk) {
        desk.state = new Open();
    }
}
