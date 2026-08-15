package io.sealfsm.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An extracted finite state machine: the complete output unit of the tool for
 * one sealed hierarchy that was classified as a state machine.
 *
 * <p>{@link #topLevelStates()} preserves the nesting hierarchy (composite
 * states hold children); {@link #allStates()} is the flattened view used for
 * completeness checks and DOT emission.
 */
public final class StateMachine {

    /**
     * Synthetic source used for <em>initial-state edges</em> (finding F7): a
     * producer reached on the path where the selector matches none of the
     * permitted subtypes (machine entry) is recorded as an edge from this
     * pseudo-state rather than mis-attributed to a real state or dropped. It is
     * deliberately not a member of {@link #allStates()}.
     */
    public static final String INITIAL_PSEUDO_STATE = "<initial>";

    /**
     * Where transition dispatch <em>lives</em> in the analysed source. This is the
     * encoding axis, and it has exactly two positions.
     *
     * <p>How the successor is <em>written</em> — bare {@code new H()}, a singleton
     * field, an enum constant, {@code this}, a local, or a value handed to a
     * carrier object — is a separate axis, {@link SuccessorForm}, resolved by one
     * uniform sub-procedure under either encoding. In particular the polymorphic
     * State pattern that returns {@code Transition.to(new LastAck(), ...)} is
     * {@link #DISTRIBUTED} dispatch with a carrier-wrapped successor, not an
     * encoding of its own: the dispatch site is identical to a per-state method
     * returning the hierarchy type, and only the spelling of the result differs.
     */
    public enum Encoding {
        /**
         * Polymorphic, per-state dispatch (classic State pattern): each permitted
         * subtype owns a transition method. The successor may be returned directly
         * or wrapped in a carrier — see {@link SuccessorForm}.
         */
        DISTRIBUTED,
        /** Centralized dispatch: a single transition function, typically a pattern-matching switch. */
        CENTRALIZED,
        /** Both dispatch positions detected, or undetermined. */
        MIXED
    }

    private final String name;            // simple name of the sealed root type
    private final String qualifiedName;   // fully-qualified name of the sealed root
    private final Encoding encoding;
    private final List<State> topLevelStates = new ArrayList<>();
    private final List<Transition> transitions = new ArrayList<>();
    private final Set<String> alphabet = new LinkedHashSet<>(); // event labels
    private final Set<SuccessorForm> successorForms = new LinkedHashSet<>();
    private String initialState;          // state id; may be null if undetected

    public StateMachine(String name, String qualifiedName, Encoding encoding) {
        this.name = Objects.requireNonNull(name, "name");
        this.qualifiedName = Objects.requireNonNull(qualifiedName, "qualifiedName");
        this.encoding = Objects.requireNonNull(encoding, "encoding");
    }

    public String name()                { return name; }
    public String qualifiedName()       { return qualifiedName; }
    public Encoding encoding()          { return encoding; }
    public List<State> topLevelStates() { return Collections.unmodifiableList(topLevelStates); }
    public List<Transition> transitions() { return Collections.unmodifiableList(transitions); }
    public Set<String> alphabet()       { return Collections.unmodifiableSet(alphabet); }
    public Optional<String> initialState() { return Optional.ofNullable(initialState); }

    public void addTopLevelState(State s) { topLevelStates.add(Objects.requireNonNull(s)); }

    public void addTransition(Transition t) {
        transitions.add(Objects.requireNonNull(t));
        if (t.event() != null) alphabet.add(t.event());
        if (t.form() != null) successorForms.add(t.form());
    }

    /**
     * The successor spellings this machine's edges actually used — the second,
     * orthogonal axis to {@link #encoding()}. Reported alongside the encoding so a
     * recall gap can be attributed to the form that caused it (say, a singleton
     * that would not resolve) rather than to the encoding it appeared under.
     */
    public Set<SuccessorForm> successorForms() {
        return Collections.unmodifiableSet(successorForms);
    }

    /**
     * Add an input symbol to the alphabet Σ independently of the transitions.
     * Used to record the <em>complete</em>, closed-world event set enumerated
     * from a sealed/enum event type (finding F4) — including events the
     * transition function ignores, which therefore appear on no edge.
     */
    public void addAlphabetSymbol(String symbol) {
        if (symbol != null && !symbol.isBlank()) alphabet.add(symbol);
    }

    public void setInitialState(String id) {
        this.initialState = id;
        allStates().forEach(s -> s.setInitial(s.id().equals(id)));
    }

    /** Depth-first flattening of the state hierarchy. */
    public List<State> allStates() {
        List<State> out = new ArrayList<>();
        for (State s : topLevelStates) collect(s, out);
        return out;
    }

    private static void collect(State s, List<State> out) {
        out.add(s);
        for (State c : s.children()) collect(c, out);
    }

    public long resolvedTransitionCount() {
        return transitions.stream().filter(Transition::isResolved).count();
    }

    public long unresolvedTransitionCount() {
        return transitions.size() - resolvedTransitionCount();
    }
}
