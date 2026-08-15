package treebuilder;

/** A literal: already in normal form, so it rewrites to itself. */
public record Lit(int value) implements Expr {

    @Override
    public Rewrite simplify() {
        // A peer-shaped production on its own — but the hierarchy is judged as a
        // whole, and Add/Neg nest, so this does not rescue it.
        return Rewrite.unchanged(this);
    }
}
