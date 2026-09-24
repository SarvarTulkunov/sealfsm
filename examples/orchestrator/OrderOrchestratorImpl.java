package orchestrator;
public class OrderOrchestratorImpl implements OrderOrchestrator {
    private final RequestValidatorService validatorService;
    private final PriceCalculator priceCalculator;
    private final PaymentBillingService paymentBillingService;
    private final ShippingService shippingService;
    public OrderOrchestratorImpl(RequestValidatorService v, PriceCalculator p, PaymentBillingService b, ShippingService s) {
        this.validatorService = v; this.priceCalculator = p; this.paymentBillingService = b; this.shippingService = s;
    }
    @Override public OrderState handle(OrderState.Placed placed) {
        var order = this.validatorService.validate(placed.request());
        return new OrderState.Validated(order);
    }
    @Override public OrderState handle(OrderState.Validated validated) {
        var priceSummary = this.priceCalculator.calculate(validated.order());
        return new OrderState.Priced(validated.order(), priceSummary);
    }
    @Override public OrderState handle(OrderState.Priced priced) {
        var invoice = this.paymentBillingService.processPayment(priced.order(), priced.priceSummary());
        return new OrderState.Invoiced(priced.order(), invoice);
    }
    @Override public OrderState handle(OrderState.Invoiced invoiced) {
        var r = this.shippingService.scheduleShipping(invoiced.order());
        return new OrderState.Shipped(invoiced.order(), invoiced.invoice(), r.shipments());
    }
    @Override public OrderState handle(OrderState.Shipped shipped) {
        return new OrderState.Fulfilled(shipped.order(), shipped.invoice(), shipped.shipments());
    }
}
