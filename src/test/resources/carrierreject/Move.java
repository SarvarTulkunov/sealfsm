package carrierreject;

/** The carrier: one hierarchy-typed slot, plus whatever rides along. */
public record Move(Cell next, String action) {
}
