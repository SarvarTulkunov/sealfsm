package typedhandler;

public interface OrderOrchestrator {
    OrderState handle(OrderState.Placed placed);
    OrderState handle(OrderState.Validated validated);
    OrderState handle(OrderState.Priced priced);
    OrderState handle(OrderState.Invoiced invoiced);
    OrderState handle(OrderState.Shipped shipped);
}
