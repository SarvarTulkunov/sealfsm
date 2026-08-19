package treebuilder;

/** Addition: constant-folds, otherwise rebuilds itself around simplified operands. */
public record Add(Expr left, Expr right) implements Expr {

    @Override
    public Rewrite simplify() {
        Rewrite l = left.simplify();
        Rewrite r = right.simplify();
        if (l.result() instanceof Lit a && r.result() instanceof Lit b) {
            return Rewrite.of(new Lit(a.value() + b.value()), 1);
        }
        // NESTED: hierarchy values appear as constructor arguments of another
        // hierarchy value. This is composition, not a transition, and it is what
        // vetoes the whole hierarchy.
        return Rewrite.of(new Add(l.result(), r.result()), 1 + l.nodes() + r.nodes());
    }
}
