package bindingframes;

/** The carrier-path F9 control's hierarchy. */
public sealed interface Winch permits Winch.Slack, Winch.Taut, Winch.Coiled {
    record Slack() implements Winch {}
    record Taut() implements Winch {}
    record Coiled() implements Winch {}
}
