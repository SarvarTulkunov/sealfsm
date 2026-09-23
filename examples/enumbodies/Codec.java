package enumbodies;

/**
 * NEGATIVE CONTROL — the shape of Kafka's {@code CompressionType}: an enum with
 * constant bodies (so implicitly sealed, JLS §8.9) and static lookups returning
 * its own type. It declares no sealed hierarchy, holds no state and changes none,
 * so it must not be a root at all — no machine, no candidate, no rejection line.
 * Admitting it published {@code forId} as a ten-edge automaton sourced at the
 * enum itself, with states named after the anonymous bodies.
 */
public enum Codec {
    NONE,
    GZIP {
        @Override
        int level() {
            return 6;
        }
    },
    ZSTD {
        @Override
        int level() {
            return 3;
        }
    };

    int level() {
        throw new UnsupportedOperationException(name());
    }

    public static Codec forId(int id) {
        switch (id) {
            case 0:
                return NONE;
            case 1:
                return GZIP;
            case 2:
                return ZSTD;
            default:
                throw new IllegalArgumentException("unknown id " + id);
        }
    }

    public static Codec forName(String name) {
        if ("none".equals(name)) return NONE;
        else if ("gzip".equals(name)) return GZIP;
        else if ("zstd".equals(name)) return ZSTD;
        else throw new IllegalArgumentException(name);
    }
}
