package chaindispatch;

/**
 * A tree rewrite wearing every outward sign of a field-mutation dispatch: a chain
 * over the hierarchy, both permitted subtypes discriminated, an H-typed field
 * assigned in each branch. Only the shape of the produced value separates it from
 * {@link RelayBoard}, and the predicate that reads it is the one the switch and
 * carrier paths already share — so a rewrite spelled with {@code instanceof} is
 * vetoed by exactly the rule that vetoes it spelled with {@code switch}.
 */
public final class TreeFolder {

    private Tree tree = new Leaf(0);

    public void fold() {
        if (tree instanceof Leaf) {
            tree = new Pair(new Leaf(1), new Leaf(2));
        } else if (tree instanceof Pair) {
            tree = new Pair(tree, new Leaf(3));
        }
    }

    public Tree tree() {
        return tree;
    }
}
