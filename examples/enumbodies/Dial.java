package enumbodies;

/**
 * A CENTRALIZED switch over the root whose arms name enum constants by their
 * qualified name ({@code case Level.LOW ->}), legal since Java 21 (JEP 441). A
 * constant label selects that constant's state exactly as a type pattern selects
 * a permitted class.
 */
public sealed interface Dial permits Idle, Level {
}
