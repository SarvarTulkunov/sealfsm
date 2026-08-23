package nestedroots;

/** A leaf part of the envelope. No lifecycle, no successor: a plain sum member. */
public record Header(String name, String value) implements Message {
}
