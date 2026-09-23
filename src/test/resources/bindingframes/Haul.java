package bindingframes;

/** The carrier the Winch dispatch commits through: one hierarchy-typed slot. */
public record Haul(Winch next) {}
