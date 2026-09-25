package typedhandler;

import java.util.List;

/**
 * The SAME order pipeline as {@code examples/orchestrator}, written with no driver:
 * only the per-state handlers {@code OrderState handle(Placed)} ... exist, and the
 * source state of each is fixed by its parameter's declared type, exactly as a
 * per-state override's is fixed by its declaring class.
 *
 * <p>F36 (thesis Decision 4) turned this fixture from "the same machine without
 * its driver" into the decision's own example of MISSING CALLER EVIDENCE. Nothing
 * here installs a handler's result as a current state. The handlers have exactly
 * the signature of {@link Length}'s converters, and nothing else in the source
 * tells the two apart. So the tool abstains: no machine, and a PROVISIONAL
 * candidate listing the six would-be states. {@code examples/orchestrator} is the
 * same pipeline WITH its run-to-completion driver, whose re-entry is the
 * store-back, and it is still a 6-state, 5/5 machine. The difference between the
 * two fixtures is now the thing the decision says it should be.
 *
 * Expected: not a machine; a candidate (INSTALLATION_UNSHOWN, 6 atomic members).
 * Before F36: 6 states, 5/5, initial Placed. Before F34: 0/1, a single
 * `<entry> -> ?`.
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
