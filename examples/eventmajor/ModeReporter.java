package eventmajor;

/** The fold: identical dispatch, identical field, codomain outside the hierarchy. */
public final class ModeReporter {

    private Mode mode = new Fast();
    private String label = "";

    public void describe(FrameType frameType) {
        switch (frameType) {
            case DATA -> label = "data";
            case SETTINGS -> label = "settings";
            case PING -> label = "ping";
            case GOAWAY -> label = "goaway";
        }
    }

    public Mode mode() {
        return mode;
    }

    public String label() {
        return label;
    }
}
