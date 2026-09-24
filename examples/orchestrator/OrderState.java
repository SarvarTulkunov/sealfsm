package orchestrator;

import java.util.List;

/**
 * A pipeline written as a RUN-TO-COMPLETION driver ({@link OrderOrchestrator#orchestrate})
 * over per-state handlers declared on an interface and implemented once
 * ({@link OrderOrchestratorImpl}). Deliberately no {@code permits} clause: the
 * records are nested in the same compilation unit, so the compiler infers it
 * (JLS §8.1.6), and the state set must still come out exact.
 *
 * Expected: 6 states, 5/5, Placed -> Validated -> Priced -> Invoiced -> Shipped
 * -> Fulfilled, initial Placed, Fulfilled terminal. Before the change: 6/31 with
 * five fabricated RESOLVED edges `X -> Fulfilled` (the driver's final result read
 * as the successor) and 25 unresolved ones.
 */
public sealed interface OrderState {
    record Placed(CreateOrderCommand request) implements OrderState {}
    record Validated(Order order) implements OrderState {}
    record Priced(Order order, PriceSummary priceSummary) implements OrderState {}
    record Invoiced(Order order, Invoice invoice) implements OrderState {}
    record Shipped(Order order, Invoice invoice, List<Shipment> shipments) implements OrderState {}
    record Fulfilled(Order order, Invoice invoice, List<Shipment> shipments) implements OrderState {}
}
