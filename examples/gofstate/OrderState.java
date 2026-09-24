package gofstate;

/**
 * F33 FIXTURE, positive 1: the classic GoF State pattern, as it is written in
 * the wild. Every input is a {@code void} method on the state interface. The
 * inputs a state does not accept are inherited {@code default} bodies that
 * throw. A state that accepts one installs its successor into the context
 * through a mutator ({@code order.changeState(...)}). There is no {@code @Fsm}
 * marker, and before F33 this whole hierarchy was rejected with no states
 * reported.
 *
 * <p>Expected: 6 states, 6/6, every edge labelled with its method name, initial
 * {@code CreatedState}, and {@code DeliveredState}/{@code CancelledState}/
 * {@code RefundedState} terminal. The 24 rejecting cells are reported, not drawn.
 */
public sealed interface OrderState
        permits CreatedState, PaidState, ShippedState, DeliveredState, CancelledState, RefundedState {

    default void pay(Order order) {
        throw new IllegalStateException("cannot pay in " + getName());
    }

    default void ship(Order order) {
        throw new IllegalStateException("cannot ship in " + getName());
    }

    default void deliver(Order order) {
        throw new IllegalStateException("cannot deliver in " + getName());
    }

    default void cancel(Order order) {
        throw new IllegalStateException("cannot cancel in " + getName());
    }

    default void refund(Order order) {
        throw new IllegalStateException("cannot refund in " + getName());
    }

    String getName();
}
