package typedhandler;

import java.util.List;

/**
 * The SAME order pipeline as {@code examples/orchestrator}, written with no driver:
 * only the per-state handlers {@code OrderState handle(Placed)} ... exist, and the
 * source state of each is fixed by its parameter's declared type, exactly as a
 * per-state override's is fixed by its declaring class. Explicit {@code permits}
 * here, implicit there — the two fixtures must report the same machine.
 *
 * Expected: 6 states, 5/5, initial Placed, Fulfilled terminal, no event labels
 * (one method name across every handler names the function, F22). Before the
 * change: 0/1, a single `<entry> -> ?`.
 */
public sealed interface OrderState
        permits OrderState.Placed, OrderState.Validated, OrderState.Priced,
                OrderState.Invoiced, OrderState.Shipped, OrderState.Fulfilled {
    record Placed(CreateOrderCommand request) implements OrderState {}
    record Validated(Order order) implements OrderState {}
    record Priced(Order order, PriceSummary priceSummary) implements OrderState {}
    record Invoiced(Order order, Invoice invoice) implements OrderState {}
    record Shipped(Order order, Invoice invoice, List<Shipment> shipments) implements OrderState {}
    record Fulfilled(Order order, Invoice invoice, List<Shipment> shipments) implements OrderState {}
}
