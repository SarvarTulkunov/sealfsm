package orchestrator;
import java.util.List;
record CreateOrderCommand(String sku) {}
record Order(String id) {}
record PriceSummary(long cents) {}
record Invoice(String id) {}
record Shipment(String id) {}
record ShippingResponse(List<Shipment> shipments) {}
interface RequestValidatorService { Order validate(CreateOrderCommand c); }
interface PriceCalculator { PriceSummary calculate(Order o); }
interface PaymentBillingService { Invoice processPayment(Order o, PriceSummary p); }
interface ShippingService { ShippingResponse scheduleShipping(Order o); }
