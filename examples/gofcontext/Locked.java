package examples.gofcontext;

public final class Locked implements Portal {
    @Override
    public void handle(PortalContext ctx, Event event) {
        if (event instanceof Unlock) {
            ctx.setState(new Closed());      // Locked -> Closed (guarded by Unlock)
        } else {
            ctx.setState(this);              // Locked -> Locked (explicit self-loop)
        }
    }
}
