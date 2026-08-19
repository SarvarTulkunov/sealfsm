package examples.gofcontext;

public final class Open implements Portal {
    @Override
    public void handle(PortalContext ctx, Event event) {
        ctx.setState(new Closed());          // Open -> Closed
    }
}
