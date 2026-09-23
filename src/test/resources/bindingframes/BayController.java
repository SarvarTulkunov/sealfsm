package bindingframes;

import java.util.ArrayList;
import java.util.List;

import bindingframes.Bay.Away;
import bindingframes.Bay.Docked;
import bindingframes.Bay.Launching;

/**
 * POSITIVE A — one hop, a VOID callee. The arm constructs the successor and
 * hands it to {@code install}, which commits it by writing the state field.
 *
 * <p>{@code install} takes TWO parameters, so it is not a recognised mutator
 * (F22 requires exactly one); the commit is proven by the k = 1 probe, which
 * reads the root-typed field write and deliberately never asks which value it
 * writes. Until the fold entered void callees, the arm could therefore only be
 * recorded as "commit proven, successor unknown" — although the successor was a
 * construction sitting in the argument list at the call site.
 *
 * <p>{@code recall} is the CONTROL inside the same dispatch: its write installs a
 * value the callee computes (an array index) from nothing the caller handed it,
 * so there is nothing to bind and the probe's gap marker must stay exactly as it
 * was.
 */
public final class BayController {

    private static final Bay[] DOCKS = { new Docked(), new Away() };

    private Bay state = new Docked();
    private final List<String> log = new ArrayList<>();

    public void on(Cmd c) {
        switch (state) {
            case Docked d -> install(new Launching(), "launch on " + c);
            case Launching l -> install(new Away(), "clear on " + c);
            case Away a -> recall(c);
        }
    }

    /** Two parameters: not a mutator. The commit is the field write, of the argument. */
    private void install(Bay next, String why) {
        log.add(why);
        this.state = next;
    }

    /** Commits a value computed here, not handed in: nothing to bind. */
    private void recall(Cmd c) {
        this.state = DOCKS[c.ordinal() % DOCKS.length];
    }
}
