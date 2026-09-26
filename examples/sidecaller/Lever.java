package sidecaller;

/**
 * F39 NEGATIVE CONTROL for the delegation rule's receiver clause. An installed
 * dispatcher calls a per-state method on a local holding the result of a first
 * transition: two transitions composed, whose source the walk cannot name, so
 * it must stay an unresolved edge. The {@code "pull"} arm beside it hands over
 * the site's own state and is the only one delegated.
 */
public sealed interface Lever permits Off, On {

    Lever onPull();

    Lever onPush();
}

final class Off implements Lever {
    @Override public Lever onPull() { return new On(); }
    @Override public Lever onPush() { return this; }
}

final class On implements Lever {
    @Override public Lever onPull() { return new Off(); }
    @Override public Lever onPush() { return this; }
}

final class LeverPanel {
    private Lever state;

    void run(String cmd) {
        state = handle(state, cmd);
    }

    void push() {
        state = state.onPush();
    }

    static Lever handle(Lever s, String cmd) {
        switch (cmd) {
            case "pull":
                return s.onPull();
            case "both": {
                Lever mid = s.onPush();
                return mid.onPull();
            }
            default:
                throw new IllegalArgumentException(cmd);
        }
    }
}
