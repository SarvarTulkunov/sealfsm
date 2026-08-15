/*
 * Corpus entry: ffmpeg status FSM.
 *
 * ADAPTED (not found in the wild as Java) from the Kotlin original
 *   FfmpegStatusStateMachine.kt, org.jitsi.jibri.capture.ffmpeg (Jitsi Jibri)
 *   Apache License 2.0, examined via Sourcegraph.
 *
 * The original defines the machine with the Tinder StateMachine builder DSL
 *   (`state<X> { on<Y> { transitionTo(Z) } }`). That DSL is configuration-as-data,
 *   not control flow, so SealFSM cannot extract from it directly — it is an
 *   adapt-only source. This file re-expresses the SAME machine (state set Q, event
 *   alphabet Sigma, transition relation delta) in the native centralized-switch
 *   idiom, reading each DSL row as one switch arm:
 *
 *       state<StartingUp> { on<EncodingLine> { transitionTo(Running) } }
 *         becomes
 *       case StartingUp -> switch(event) { case EncodingLine -> new Running(); ... }
 *
 * Modelling decisions:
 *   - `dontTransition()`  ->  `case ... -> current`  (self-loop).
 *   - `state<Error> { on(any()) { dontTransition() } }`  ->  the Error / Finished
 *     arms return `current` for every event (absorbing terminal states).
 *   - `it.error?.let { Error(it) } ?: Finished`  ->  a guarded ternary on whether
 *     the exit carried an error.
 *   - SideEffect and the onTransition notifier are Mealy-style output, dropped.
 *   - `FfmpegOutputStatus.toFfmpegEvent(...)` is an event PARSER from external
 *     input (a factory), not part of delta, and is omitted.
 *   - Event payloads irrelevant to delta (the raw output line) are dropped; only
 *     the JibriError needed for the Error state / the exit guard is kept.
 *
 * ComponentState is shared with the Jibri service machine in the original; here it
 * is inlined so the entry is self-contained.
 */
package examples.ffmpeg;

public final class FfmpegStatusStateMachine {

    /** Minimal stand-in for org.jitsi.jibri.error.JibriError. */
    public record JibriError(String detail) {}

    // ----- State set Q (exact, from the permits clause) -----------------------
    public sealed interface ComponentState
            permits ComponentState.StartingUp, ComponentState.Running,
                    ComponentState.Error, ComponentState.Finished {
        record StartingUp() implements ComponentState {}                 // initial
        record Running() implements ComponentState {}
        record Error(JibriError error) implements ComponentState {}      // terminal (absorbing)
        record Finished() implements ComponentState {}                    // terminal (absorbing)
    }

    // ----- Event alphabet Sigma (exact, from the sealed event type) -----------
    public sealed interface FfmpegEvent
            permits FfmpegEvent.EncodingLine, FfmpegEvent.ErrorLine, FfmpegEvent.FinishLine,
                    FfmpegEvent.OtherLine, FfmpegEvent.FfmpegExited {
        record EncodingLine() implements FfmpegEvent {}
        record ErrorLine(JibriError error) implements FfmpegEvent {}
        record FinishLine() implements FfmpegEvent {}
        record OtherLine() implements FfmpegEvent {}
        /** error may be null: a "normal" last line still means ffmpeg exited. */
        record FfmpegExited(JibriError error) implements FfmpegEvent {}
    }

    private ComponentState state = new ComponentState.StartingUp();  // initial state

    public ComponentState state() {
        return state;
    }

    /** Drive the FSM with one event (field-mutation transition site). */
    public void onEvent(FfmpegEvent event) {
        this.state = transition(this.state, event);
    }

    /**
     * The transition function delta: ComponentState x FfmpegEvent -> ComponentState.
     * State-major centralized switch; the nested switch selects the event.
     */
    public ComponentState transition(ComponentState current, FfmpegEvent event) {
        return switch (current) {

            case ComponentState.StartingUp s -> switch (event) {
                case FfmpegEvent.EncodingLine e -> new ComponentState.Running();
                case FfmpegEvent.ErrorLine e    -> new ComponentState.Error(e.error());
                case FfmpegEvent.FinishLine e   -> new ComponentState.Finished();
                case FfmpegEvent.OtherLine e    -> current;                    // dontTransition
                case FfmpegEvent.FfmpegExited e -> e.error() != null
                        ? new ComponentState.Error(e.error())
                        : new ComponentState.Finished();
            };

            case ComponentState.Running r -> switch (event) {
                case FfmpegEvent.EncodingLine e -> current;                    // dontTransition
                case FfmpegEvent.ErrorLine e    -> new ComponentState.Error(e.error());
                case FfmpegEvent.FinishLine e   -> new ComponentState.Finished();
                case FfmpegEvent.OtherLine e    -> current;                    // dontTransition
                case FfmpegEvent.FfmpegExited e -> e.error() != null
                        ? new ComponentState.Error(e.error())
                        : new ComponentState.Finished();
            };

            // Terminal, absorbing: any event keeps the machine where it is.
            case ComponentState.Error er   -> current;
            case ComponentState.Finished f -> current;
        };
    }
}
