package gofstate;

public final class Bus {
    private Message last;

    public void publish(Message message) {
        this.last = message;
    }
}
