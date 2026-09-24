package gofstate;

public final class CancelledState implements OrderState {
    @Override
    public String getName() {
        return "cancelled";
    }
}
