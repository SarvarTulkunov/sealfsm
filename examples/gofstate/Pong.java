package gofstate;

public final class Pong implements Message {
    @Override
    public void send(Bus bus) {
        bus.publish(this);
    }
}
