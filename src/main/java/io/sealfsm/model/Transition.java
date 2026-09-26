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
    private final String origin; // unresolved only: the program point that produced the gap
    private SuccessorForm form;  // how the target was written; null if not attributed
    private boolean otherwise;   // reached via else/default with no event selecting it

    private Transition(String from, String to, String event, String guard,
                       boolean resolved, String note, String origin) {
        this.from = Objects.requireNonNull(from, "from");
        this.to = to;
        this.event = event;
        this.guard = guard;
        this.resolved = resolved;
        this.note = note;
        this.origin = resolved ? null : origin;
    }

    /** A fully resolved transition with a known target state. */
    public static Transition resolved(String from, String to, String event, String guard) {
        return new Transition(from, to, event, guard, true, null, null);
    }

    /** A detected-but-unresolved transition; {@code rawTarget} is the offending source. */
    public static Transition unresolved(String from, String event, String guard, String rawTarget) {
        return new Transition(from, null, event, guard, false, rawTarget, null);
    }

    /**
     * An unresolved transition produced at {@code origin}, a stable key for the
     * program point that computes the unknown successor (a source position).
     *
     * <p>A gap's identity is WHERE its successor is computed, not only which state
     * it leaves: two different expressions in one state, under one event and one
     * guard, are two unknown successors, and nothing says they are the same one.
     * Without the origin they compared equal and a set of transitions kept one —
     * a recorded gap dropped with no marker. A resolved edge has no origin: its
     * identity is the relation tuple, and two code paths yielding {@code A -e-> B}
     * are one transition.
     */
    public static Transition unresolved(String from, String event, String guard, String rawTarget,
                                        String origin) {
        return new Transition(from, null, event, guard, false, rawTarget, origin);
    }

    /**
     * Record how the successor was written ({@code new X()}, a singleton, an enum
     * constant, ...). Descriptive metadata for the evaluation, deliberately kept
     * out of {@link #equals} so two edges that differ only in spelling still
     * deduplicate to one edge of the transition relation.
     */
    public Transition withForm(SuccessorForm f) {
        this.form = f;
        return this;
    }

    /**
     * Mark this as the <em>default</em> ("otherwise") edge: it was reached through
     * an {@code else}, a {@code default} arm or a fall-through, and no event test
     * selected it. This is a legitimate, fully-specified transition — the residual
     * of the guards that precede it — not an error and not a candidate for
     * dropping. It is recorded explicitly so downstream consumers can tell "no
     * event was found" apart from "this edge fires when nothing else does".
     */
    public Transition asOtherwise() {
        this.otherwise = true;
        return this;
    }

    public String from()      { return from; }
    public String to()        { return to; }
    public String event()     { return event; }
    public String guard()     { return guard; }
    public boolean isResolved() { return resolved; }
    public String note()      { return note; }
    /** The program point that produced an unresolved transition; {@code null} when resolved or unknown. */
    public String origin()    { return origin; }
    public SuccessorForm form() { return form; }
    public boolean isOtherwise() { return otherwise; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transition t)) return false;
        return resolved == t.resolved
                && from.equals(t.from)
                && Objects.equals(to, t.to)
                && Objects.equals(event, t.event)
                && Objects.equals(guard, t.guard)
                // A gap is identified by the program point, and the expression
                // there, that computes its unknown successor (see unresolved).
                && (resolved || (Objects.equals(origin, t.origin) && Objects.equals(note, t.note)));
    }

    @Override
    public int hashCode() {
        return resolved
                ? Objects.hash(from, to, event, guard, true)
                : Objects.hash(from, event, guard, false, origin, note);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(from).append(" --");
        if (event != null) sb.append(event);
        else if (otherwise) sb.append("otherwise");
        if (guard != null) sb.append('[').append(guard).append(']');
        sb.append("--> ");
        sb.append(resolved ? to : "??(" + note + ")");
        return sb.toString();
    }
}
