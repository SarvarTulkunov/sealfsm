package nestedroots;

/**
 * F36 control (thesis Decision 4): a persistent-tree update stored back into the
 * field it read, {@code branch = branch.replaceChild(child)}.
 *
 * <p>Structurally this is exactly the store-back F36 accepts as installing a
 * successor as the current state. That is why it is here. It keeps
 * {@code Branch} a machine when classified on its own, so the compositional veto
 * on {@code Node} still has something to prevent, and it shows that the
 * store-back rule does not make the veto redundant. A tree rebuilt and stored is
 * still a tree.
 */
final class Rebuilder {
    private Branch branch;

    Rebuilder(Branch start) {
        this.branch = start;
    }

    void swap(Node child) {
        branch = branch.replaceChild(child);
    }
}
