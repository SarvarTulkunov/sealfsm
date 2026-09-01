package foldbinding;

import java.util.List;

/** The wrapper the dispatch commits through: exactly one hierarchy-typed slot. */
public record Carriage(Carry state, List<String> actions) {}
