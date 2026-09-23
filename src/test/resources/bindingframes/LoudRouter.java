package bindingframes;

/** The second implementation that makes a Router-typed call virtual. */
public final class LoudRouter extends Router {

    @Override
    public Pump pick(Pump p) {
        return new Pump.Idle();
    }

    @Override
    public Pump reject(Pump p) {
        return new Pump.Idle();
    }
}
