package unreadablecallee;

/**
 * The unreadable callee. An interface method with <b>no implementation anywhere
 * in the source set</b>, so Spoon supplies a declaration with no body.
 *
 * <p>It is deliberately shaped exactly like {@code voidcommit.HopperDriver.install}
 * — one hierarchy-typed parameter, one event parameter, {@code void} — so that
 * nothing but the absence of a body can be what the probe reacts to. Give this
 * method a body that writes a {@code Shutter}-typed field and the fixture becomes
 * {@code examples/voidcommit}; that is the measure of how narrow the difference
 * is.
 */
public interface ShutterSink {

    void install(Shutter current, Command command);
}
