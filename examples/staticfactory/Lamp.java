package staticfactory;

/**
 * A real POLYMORPHIC machine carrying the three static methods a State-pattern
 * hierarchy ordinarily does, so the exclusion is tested where it has something to
 * lose rather than on a hierarchy with nothing else in it.
 *
 * <ul>
 *   <li>{@link #initial()} — a factory on the root. Read as a per-state method it
 *       sources an edge at {@code Lamp}, the root interface, which is not a
 *       state.</li>
 *   <li>{@link Off#create()} — a factory on a LEAF, and the sharper of the two:
 *       read as a per-state method it publishes {@code Off -> Off} as a resolved
 *       self-loop, sourced at a real state and indistinguishable from a real
 *       transition. Nothing about the output would give it away.</li>
 *   <li>{@link On}'s private static helper — the RECALL control. It returns the
 *       hierarchy type and is reached from {@code On.toggle()}, so the successor
 *       arrives through the F3 fold. Excluding statics from the per-state set
 *       must not stop the fold entering one, or {@code On -> Off} is lost.</li>
 * </ul>
 *
 * <p>Off --toggle--> On --toggle--> Off. 2 states, 2 edges, both resolved.
 */
public sealed interface Lamp permits Off, On {

    Lamp toggle();

    static Lamp initial() {
        return new Off();
    }
}
