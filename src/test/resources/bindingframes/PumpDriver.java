package bindingframes;

import bindingframes.Pump.Idle;
import bindingframes.Pump.Primed;
import bindingframes.Pump.Running;

/**
 * NEGATIVE B — a virtual call with two implementations in the model. Spoon binds
 * {@code router.pick(...)} to {@code Router#pick}; folding that body alone
 * publishes {@code Idle -> Primed} as RESOLVED and never mentions that a
 * {@link LoudRouter} sends the pump to {@code Idle}. The call has no unique body,
 * so it is recorded UNRESOLVED, edge present.
 *
 * <ul>
 *   <li>{@code Primed} is the CONTROL, and it is the same method: the receiver's
 *       STATIC type is {@link PlainRouter}, which is final and overrides nothing,
 *       so the body that runs is unique and the binding resolves. Only the
 *       receiver's type differs from the {@code Idle} arm.</li>
 *   <li>{@code Running} is the sharper direction. {@code Router#reject} always
 *       throws, so F9 read off the bound body DELETED this arm's edge — while
 *       {@code LoudRouter#reject} returns a state. F9 is exact about the body it
 *       reads; it may only read the body that runs.</li>
 * </ul>
 */
public final class PumpDriver {

    public static Pump next(Pump current, Router router, PlainRouter plain, Cmd c) {
        return switch (current) {
            case Idle i -> router.pick(new Primed());
            case Primed p -> plain.pick(new Running());
            case Running r -> router.reject(r);
        };
    }
}
