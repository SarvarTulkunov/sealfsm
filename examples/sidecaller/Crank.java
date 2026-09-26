package sidecaller;

/**
 * F39 NEGATIVE CONTROL for the delegation rule's reassignment clause. The
 * dispatcher's receiver is its selector BY NAME, but the method reassigns it
 * first, so what it holds at the call is the result of a first transition. Two
 * transitions composed; the edge must stay unresolved.
 */
public sealed interface Crank permits Low, High {

    Crank onTurn();

    Crank onHold();
}

final class Low implements Crank {
    @Override public Crank onTurn() { return new High(); }
    @Override public Crank onHold() { return this; }
}

final class High implements Crank {
    @Override public Crank onTurn() { return new Low(); }
    @Override public Crank onHold() { return this; }
}

final class CrankPanel {
    private Crank state;

    void again() {
        state = twice(state);
    }

    static Crank twice(Crank s) {
        s = s.onHold();
        return s.onTurn();
    }
}
