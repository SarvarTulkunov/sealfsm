package gofstate;

/** The GoF context: holds the current state and exposes the mutator states call. */
public final class Order {
    private final String orderId;
    private OrderState state = new CreatedState();

    public Order(String orderId) {
        this.orderId = orderId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void changeState(OrderState next) {
        this.state = next;
    }

    public void pay() { state.pay(this); }
    public void ship() { state.ship(this); }
    public void deliver() { state.deliver(this); }
    public void cancel() { state.cancel(this); }
    public void refund() { state.refund(this); }
}
