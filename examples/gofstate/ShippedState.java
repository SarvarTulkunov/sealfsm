package gofstate;

public final class ShippedState implements OrderState {
    @Override
    public void deliver(Order order) {
        System.out.println("order " + order.getOrderId() + " delivered");
        order.changeState(new DeliveredState());
    }

    @Override
    public void refund(Order order) {
        System.out.println("order " + order.getOrderId() + " intercepted, refunded");
        order.changeState(new RefundedState());
    }

    @Override
    public String getName() {
        return "shipped";
    }
}
