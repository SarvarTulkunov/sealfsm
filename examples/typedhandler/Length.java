package typedhandler;

/**
 * F36 MISSING-CALLER CONTROL (thesis Decision 4): two converters from two
 * different states, and no caller anywhere in the source set.
 *
 * <p>Until F36 this sum type was published as a machine ({@code Meters <-> Feet}):
 * two typed handlers cleared F35's family threshold, and a value-returning host
 * was committed by its codomain alone. It was pinned as a wrong answer on
 * purpose (LIMITATIONS.md L3). Now a returned hierarchy value must be shown
 * becoming the current state before it is a transition, and nothing here shows
 * that.
 *
 * <p>Missing caller evidence is UNCERTAINTY, not proof of a conversion. A public
 * transition function in a library looks exactly like this. So the verdict is an
 * abstention: no machine, and a PROVISIONAL candidate, because two handlers
 * fixing two source states amount to a plausible dispatch. {@link Temperature} is
 * the same shape with callers that use the results as data, and it is rejected
 * outright. {@link OrderState} (this package) is a genuine pipeline written the
 * same way, and it gets the same abstention, because nothing in its source tells
 * it apart from this one either.
 */
public sealed interface Length permits Length.Meters, Length.Feet {
    record Meters(double v) implements Length {}
    record Feet(double v) implements Length {}
}

final class Units {
    static Length toFeet(Length.Meters m) { return new Length.Feet(m.v() * 3.28084); }
    static Length toMeters(Length.Feet f) { return new Length.Meters(f.v() / 3.28084); }
}
