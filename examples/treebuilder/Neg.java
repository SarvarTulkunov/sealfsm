package treebuilder;

/** Negation: {@code --x} collapses, otherwise the operand is rewritten in place. */
public record Neg(Expr operand) implements Expr {

    @Override
    public Rewrite simplify() {
        if (operand instanceof Neg inner) {
            // Returns a *child* of this node, pulled out of the tree. Peer-shaped
            // by position, but the sibling test looks at the hierarchy as a whole.
            return Rewrite.of(inner.operand(), 1);
        }
        // NESTED: the new Expr is built *around* another Expr.
        return Rewrite.of(new Neg(operand.simplify().result()), 2);
    }
}
