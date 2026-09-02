package eventmajor;

/** One committing arm; every other arm does bookkeeping the commit forms ignore. */
public final class BeatBox {

    private Beat beat = new Up();
    private int pings;

    public void tick(FrameType frameType) {
        switch (frameType) {
            case DATA -> pings++;
            case SETTINGS -> pings += 2;
            case PING -> pings += 3;
            case GOAWAY -> beat = new Down();
        }
    }

    public Beat beat() {
        return beat;
    }
}
