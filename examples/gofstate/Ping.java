package gofstate;

public final class Ping implements Message {
    @Override
    public void send(Bus bus) {
        bus.publish(this);
    }
}
