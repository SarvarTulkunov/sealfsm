package foldbinding;

/** CARRIER_RETURN half of the F25 fixture: the successor is a slot in a wrapper. */
public sealed interface Carry permits Carry.Parked, Carry.Rolling, Carry.Docked {
    record Parked() implements Carry {}
    record Rolling() implements Carry {}
    record Docked() implements Carry {}
}
