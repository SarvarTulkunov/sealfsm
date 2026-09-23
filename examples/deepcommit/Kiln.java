package deepcommit;

/**
 * {@link #tick()} is a real transition table: Cold -> Warm -> Hot -> Cold. Its arms
 * are bare calls, and each commit is TWO calls deep ({@code heat()} calls
 * {@code install(...)}, which writes the field). The k = 1 commit probe opens
 * {@code heat()} and finds no field write in it, so no commit is proven. That is
 * the documented depth bound, and the hierarchy is correctly a candidate rather
 * than a machine. What the candidate may NOT say is that this dispatch folds: it
 * produces no value at all.
 *
 * <p>{@link #describe()} is the NEGATIVE CONTROL, and it really is a fold: the same
 * discrimination, folded into {@code String}. It must still be reported under the
 * exhaustive-fold sentence, so F32 cannot degrade into never saying "fold".
 */
public final class Kiln {

    private Phase phase = new Phase.Cold();

    public void tick() {
        switch (phase) {
            case Phase.Cold c -> heat();
            case Phase.Warm w -> overheat();
            case Phase.Hot h -> cool();
        }
    }

    public String describe() {
        return switch (phase) {
            case Phase.Cold c -> "cold";
            case Phase.Warm w -> "warm";
            case Phase.Hot h -> "hot";
        };
    }

    private void heat() {
        install(new Phase.Warm());
    }

    private void overheat() {
        install(new Phase.Hot());
    }

    private void cool() {
        install(new Phase.Cold());
    }

    private void install(Phase next) {
        this.phase = next;
    }
}
