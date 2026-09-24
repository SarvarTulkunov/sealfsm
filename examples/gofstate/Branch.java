package gofstate;

public final class Branch implements Node {
    private Node left;

    public void setLeft(Node child) {
        this.left = child;
    }

    public void prune() {
        this.left = new Leaf();
    }
}
