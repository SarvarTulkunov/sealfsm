package foldbinding;

/** The hierarchy for the non-selector-parameter control. */
public sealed interface Ballast permits Ballast.Trim, Ballast.List_ {
    record Trim() implements Ballast {}
    record List_() implements Ballast {}
}
