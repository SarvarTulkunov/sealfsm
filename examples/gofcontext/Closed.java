package examples.gofcontext;

public final class Closed implements Portal {
    @Override
    public void handle(PortalContext ctx, Event event) {
        if (event instanceof Lock) {
            ctx.setState(new Locked());      // Closed -> Locked (guarded by Lock)
        } else {
            ctx.setState(new Open());        // Closed -> Open (else branch)
        }
    }
}
