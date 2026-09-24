package gofstate;

public final class RefundedState implements OrderState {
    @Override
    public String getName() {
        return "refunded";
    }
}
