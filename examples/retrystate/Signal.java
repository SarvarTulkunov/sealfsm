package retrystate;

/** Σ for {@link Attempt}: a flat enum, so the alphabet is exact and small. */
public enum Signal {
    START, FAIL, SUCCEED, GIVE_UP
}
