package eventmajor;

/**
 * The input alphabet, shared by every hierarchy in this fixture so that Σ is held
 * FIXED and only the thing under test varies.
 */
public enum FrameType { DATA, SETTINGS, PING, GOAWAY }
