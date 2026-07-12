package io.sealfsm.model;

import java.util.Objects;

/**
 * A directed edge between two states.
 *
 * <p>Unlike states, transitions are recovered through intra-procedural
 * data-flow analysis and are therefore <em>approximate</em>. When the analyzer
 * can prove the concrete target state, {@link #isResolved()} is {@code true}
 * and {@link #to()} names that state. When it cannot (e.g. the target comes
 * from an inter-procedural call, an unrecognised expression shape, or a
 * Spoon-version-specific AST node the resolver does not handle), the transition
 * is still recorded with {@code resolved == false} and {@link #note()} carries
 * the raw source fragment. This makes missed edges <em>visible</em> in the
 * output and in recall metrics rather than silently dropped.
 */
public final class Transition {

    private final String from;   // source state id (never null)
    private final String to;     // target state id; null when unresolved
    private final String event;  // trigger/event label; null if not modelled
    private final String guard;  // guard condition source text; null if none
    private final boolean resolved;
    private final String note;   // diagnostic: raw target text when unresolved

    private Transition(String from, String to, String event, String guard,
                       boolean resolved, String note) {
        this.from = Objects.requireNonNull(from, "from");
        this.to = to;
        this.event = event;
        this.guard = guard;
        this.resolved = resolved;
        this.note = note;
    }

    /** A fully resolved transition with a known target state. */
    public static Transition resolved(String from, String to, String event, String guard) {
        return new Transition(from, to, event, guard, true, null);
    }

    /** A detected-but-unresolved transition; {@code rawTarget} is the offending source. */
    public static Transition unresolved(String from, String event, String guard, String rawTarget) {
        return new Transition(from, null, event, guard, false, rawTarget);
    }

    public String from()      { return from; }
    public String to()        { return to; }
    public String event()     { return event; }
    public String guard()     { return guard; }
    public boolean isResolved() { return resolved; }
    public String note()      { return note; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transition t)) return false;
        return resolved == t.resolved
                && from.equals(t.from)
                && Objects.equals(to, t.to)
                && Objects.equals(event, t.event)
                && Objects.equals(guard, t.guard);
    }

    @Override
    public int hashCode() {
        return Objects.hash(from, to, event, guard, resolved);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(from).append(" --");
        if (event != null) sb.append(event);
        if (guard != null) sb.append('[').append(guard).append(']');
        sb.append("--> ");
        sb.append(resolved ? to : "??(" + note + ")");
        return sb.toString();
    }
}
