package bindingframes;

/** Always throws here; {@link LenientGate} returns a carrier instead. */
public class Gate {
    public Haul refuse(Winch w) {
        throw new IllegalStateException("refused in " + w);
    }
}

/** The second body: a Gate-typed call to refuse may return normally. */
final class LenientGate extends Gate {
    @Override
    public Haul refuse(Winch w) {
        return new Haul(new Winch.Slack());
    }
}

/** Overrides nothing and is final: a StrictGate-typed call has exactly one body. */
final class StrictGate extends Gate {
}
