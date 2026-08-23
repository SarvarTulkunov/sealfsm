package namecollision;

/**
 * Holder for the superseded nested state. Not itself part of the hierarchy — it
 * only carries {@link Legacy.Idle}, whose simple name is identical to the
 * top-level {@link namecollision.Idle}.
 */
public final class Legacy {

    /** The superseded idle state, kept for compatibility. */
    public static final class Idle implements Link {
    }
}
