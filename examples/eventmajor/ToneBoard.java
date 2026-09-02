package eventmajor;

/** The same table over an OPEN selector: only the selector's type differs. */
public final class ToneBoard {

    private Tone tone = new Soft();

    public void apply(int code) {
        switch (code) {
            case 1 -> tone = new Soft();
            case 2 -> tone = new Loud();
            default -> tone = new Soft();
        }
    }

    public Tone tone() {
        return tone;
    }
}
