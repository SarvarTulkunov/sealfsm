package enumbodies;

/**
 * A declared sealed machine one of whose permitted subtypes is an enum with
 * constant BODIES. JLS §8.9 makes that enum implicitly sealed, and Spoon reports
 * the modifier — so read naively it is a sealed composite whose "permitted
 * subtypes" are the anonymous bodies {@code Lit$1}, {@code Lit$2}. Its states are
 * its constants, DIM and BRIGHT, exactly as for an enum without bodies.
 */
public sealed interface Lamp permits Off, Lit {
    Lamp press();
}
