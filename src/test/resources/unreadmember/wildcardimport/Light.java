package wildcardimport;

/**
 * The RECALL-GAP control. A wildcard import of an unreadable package leaves Spoon
 * unable to qualify the name at all, so the use site arrives as a bare `Amber`.
 * Per JLS 7.5.2 a same-package type shadows a wildcard import, so the reference
 * really does denote the permitted state and the edge is real — recovery simply
 * cannot see that, and records the gap instead of guessing past it.
 */
public sealed interface Light permits Red, Amber {
    Light next();
}
