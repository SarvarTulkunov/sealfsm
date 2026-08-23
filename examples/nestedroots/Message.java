package nestedroots;

/**
 * The OUTER hierarchy, and not a state machine: nothing anywhere returns a
 * {@code Message} value, so every recognizer abstains.
 *
 * <p>It is also not a fold, an event alphabet or a tree — it is the ordinary
 * shape of a protocol envelope, one of whose parts happens to have a lifecycle.
 * {@link Body} is that part, and is a state machine in its own right.
 *
 * <p>Because {@code Body} is sealed, {@code SealedHierarchyDetector} withholds
 * it from the root list, on the reasoning that it is a composite state of this
 * hierarchy. That reasoning lapses the moment this hierarchy is rejected, and
 * the child then has to be re-offered as a root — otherwise the only thing the
 * tool ever reports about this file is that {@code Message} was skipped.
 */
public sealed interface Message permits Header, Body {
}
