package bindingframes;

/**
 * POSITIVE C — {@code this} inside a folded callee is the RECEIVER the caller
 * wrote. {@code hold()} is declared once and overridden nowhere, so every call to
 * it has exactly one body, and that body returns its receiver.
 *
 * <p>Before the receiver slot existed, every {@code this} in a folded body read
 * as a self-loop to the caller's from-state. On this hierarchy that published a
 * clean 5/5 in which every edge was a self-loop — {@code Park} could never leave
 * {@code Park} — while the source moves Park to Drive and Drive to Reverse.
 *
 * <ul>
 *   <li>{@code Park}: a CONSTRUCTED receiver ({@code new Drive().hold()}) is the
 *       successor; {@code this.hold()} is the genuine self-loop.</li>
 *   <li>{@code Drive}: a receiver produced by a STATIC FACTORY is folded in the
 *       frame it is written in; the unqualified {@code hold()} is the genuine
 *       self-loop, through an implicit {@code this}.</li>
 *   <li>{@code Reverse}: the CONTROL. The receiver comes from an interface method
 *       with no implementation in the source set, so the successor cannot be
 *       read: UNRESOLVED, where it used to be a fabricated self-loop.</li>
 * </ul>
 */
public sealed interface Gear permits Gear.Park, Gear.Drive, Gear.Reverse {

    Gear shift(Cmd c);

    /** Returns its receiver. One body, so a call to it dispatches nowhere else. */
    default Gear hold() {
        return this;
    }

    record Park() implements Gear {
        public Gear shift(Cmd c) {
            if (c == Cmd.GO) return new Drive().hold();
            return this.hold();
        }
    }

    record Drive() implements Gear {
        public Gear shift(Cmd c) {
            if (c == Cmd.BACK) return Gears.reverse().hold();
            return hold();
        }
    }

    record Reverse() implements Gear {
        public Gear shift(Cmd c) {
            return Gears.TABLE.lookup(c).hold();
        }
    }
}
