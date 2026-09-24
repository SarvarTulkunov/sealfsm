package gofstate;

public final class CreatedState implements OrderState {
    @Override
    public void pay(Order order) {
        System.out.println("order " + order.getOrderId() + " paid");
        order.changeState(new PaidState());
    }

    @Override
    public void cancel(Order order) {
        System.out.println("order " + order.getOrderId() + " cancelled");
        order.changeState(new CancelledState());
    }

    @Override
    public String getName() {
        return "created";
    }
}
