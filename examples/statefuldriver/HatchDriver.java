package statefuldriver;

/** Field-mutation commit, then a read-back the walk must not mistake for a successor. */
public final class HatchDriver {

    private Hatch state = new Dogged();

    public Hatch cycle(Turn turn) {
        state = switch (state) {
            case Dogged d -> turn == Turn.EASE ? new Cracked() : d;
            case Cracked c -> turn == Turn.HAUL ? new Gaping() : c;
            case Gaping g -> turn == Turn.DOG ? new Dogged() : g;
        };
        return state;
    }
}
