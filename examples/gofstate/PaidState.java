package gofstate;

public final class PaidState implements OrderState {
    @Override
    public void ship(Order order) {
        order.changeState(new ShippedState());
    }

    @Override
    public void refund(Order order) {
        order.changeState(new RefundedState());
    }

    @Override
    public String getName() {
        return "paid";
    }
}
