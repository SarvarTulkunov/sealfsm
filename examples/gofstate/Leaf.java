package gofstate;

public final class Leaf implements Node {
    public void attachTo(Branch parent) {
        parent.setLeft(new Branch());
    }
}
