package treebuilder;

/**
 * The same recursive tree rewrite, written as a CENTRALIZED switch over the
 * hierarchy and committed by value return — the exact shape the widened
 * recognizer accepts for a state machine.
 *
 * <p>It exists so the sibling-vs-nested guard is exercised on the centralized
 * path and not only on the carrier one. Every surface signal matches a
 * transition function: one exhaustive switch over the sealed type, arms
 * producing sealed values, an {@code Expr}-typed commit. The only thing that
 * differs is the POSITION of the produced values — {@code new Add(fold(left),
 * fold(right))} makes a bigger {@code Expr} out of smaller ones instead of
 * naming a peer of the current one — and that alone must decide rejection.
 */
public final class CentralRewriter {

    private CentralRewriter() {
    }

    public static Expr fold(Expr e) {
        return switch (e) {
            case Lit l -> l;
            case Neg n -> new Neg(fold(n.operand()));
            // NESTED: hierarchy values as constructor arguments of a hierarchy node.
            case Add a -> new Add(fold(a.left()), fold(a.right()));
        };
    }
}
