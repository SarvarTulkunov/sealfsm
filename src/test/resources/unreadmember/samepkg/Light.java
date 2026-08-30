package samepkg;

/**
 * The intended recovery case: `Amber` has no declaration in the analysed set, and
 * the file that constructs it is in this same package with no import, so Spoon
 * guesses `samepkg.Amber` for both the permits reference and the use site.
 */
public sealed interface Light permits Red, Amber {
    Light next();
}
