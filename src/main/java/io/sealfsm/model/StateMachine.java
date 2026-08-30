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
     * <p>Two further properties are deliberately <em>not</em> positions on this
     * axis, because they vary independently of it:
     * <ul>
     *   <li>how the successor is <em>written</em> — bare {@code new H()}, a
     *       singleton field, an enum constant, {@code this}, a local, or a value
     *       handed to a carrier object — is {@link SuccessorForm};</li>
     *   <li>how the successor is <em>installed</em> — returned, written to a
     *       field, accumulated in a local, or wrapped in a carrier — is
     *       {@link CommitForm}.</li>
     * </ul>
     * In particular the polymorphic State pattern that returns
     * {@code Transition.to(new LastAck(), ...)} is {@link #POLYMORPHIC} dispatch
     * with a carrier commit, not an encoding of its own: the dispatch site is
     * identical to a per-state method returning the hierarchy type, and only the
     * spelling and installation of the result differ.
     */
    public enum Encoding {
        /**
         * Polymorphic, per-state dispatch (classic State pattern): each permitted
         * subtype owns a transition method. The successor may be returned directly
         * or wrapped in a carrier — see {@link SuccessorForm} and {@link CommitForm}.
         */
        POLYMORPHIC,
        /**
         * Centralized dispatch: one switch over the hierarchy type computes the
         * transitions, wherever that switch is hosted and however its result is
         * committed.
         */
        CENTRALIZED_DISPATCH,
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
    private final Set<CommitForm> commitForms = new LinkedHashSet<>();
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

    /**
     * State ids carried by more than one state — the empty set for every
     * well-formed machine.
     *
     * <p>An id is the identity a {@link Transition} endpoint, a DOT node and an
     * SCXML {@code id} all refer to, so two states sharing one is not a cosmetic
     * problem: their edges become the same {@code (from, to, event, guard)} tuple
     * and the extractor's transition set silently discards the duplicate, dropping
     * a real transition with no unresolved marker. {@link StateNaming} assigns ids
     * so that this cannot happen; this check exists because the assignment and the
     * extractor are separate pieces of code, and a drift between them would
     * otherwise be invisible in the output. Reported as a diagnostic rather than
     * thrown — a wrong diagram on someone's repository is bad, a crash is worse.
     */
    public Set<String> duplicateStateIds() {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (State s : allStates()) {
            if (!seen.add(s.id())) duplicates.add(s.id());
        }
        return duplicates;
    }

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
     * The commit mechanisms this machine's dispatch used — the third axis,
     * orthogonal to both {@link #encoding()} and {@link #successorForms()}. Two
     * machines can share an encoding yet differ entirely in how well their
     * successors are recovered, because one returns its result and the other
     * writes it to a field; reporting the commit form is what makes that
     * difference visible in the evaluation instead of averaged away.
     */
    public Set<CommitForm> commitForms() {
        return Collections.unmodifiableSet(commitForms);
    }

    /** Record a commit mechanism observed while extracting this machine. */
    public void addCommitForm(CommitForm form) {
        if (form != null) commitForms.add(form);
    }

    /**
     * Mark the states with no outgoing transition at all as <em>terminal</em>.
     *
     * <p>Only states the dispatch actually <em>matched</em> are eligible: a state
     * an arm selected and from which every path throws is genuinely absorbing
     * (RFC 9113's {@code Closed} is the canonical case), whereas a state the
     * dispatch never mentioned has no outgoing edges only because none were
     * recovered. Calling the latter terminal would upgrade a recall gap into a
     * positive claim, which is exactly what the soundness invariant forbids.
     *
     * @param dispatched ids of the states a dispatch arm (or a per-state
     *                   transition method) selected
     */
    public void markTerminalStates(Set<String> dispatched) {
        Set<String> withOutgoing = new LinkedHashSet<>();
        for (Transition t : transitions) withOutgoing.add(t.from());
        for (State s : allStates()) {
            s.setTerminal(dispatched.contains(s.id()) && !withOutgoing.contains(s.id()));
        }
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

    /**
     * The states enumerated from a {@code permits} reference whose declaration was
     * never read — see {@link State#isDeclarationUnread()}. Empty for every input
     * whose whole hierarchy was readable, which is every well-formed run.
     */
    public Set<String> statesWithUnreadDeclaration() {
        Set<String> out = new LinkedHashSet<>();
        for (State s : allStates()) {
            if (s.isDeclarationUnread()) out.add(s.id());
        }
        return out;
    }

    /**
     * Edges with at least one endpoint whose declaration was never read.
     *
     * <p>The quantity a stratified recall table needs: these edges were matched
     * through a guessed qualified name rather than against a declaration, so
     * pooling them with the rest would report two different strengths of evidence
     * as one number. Derived rather than stored, so it cannot drift from the
     * flags on the states.
     */
    public long transitionsViaUnreadDeclaration() {
        Set<String> unread = statesWithUnreadDeclaration();
        if (unread.isEmpty()) return 0;
        return transitions.stream()
                .filter(t -> unread.contains(t.from()) || (t.to() != null && unread.contains(t.to())))
                .count();
    }

    public long resolvedTransitionCount() {
        return transitions.stream().filter(Transition::isResolved).count();
    }

    public long unresolvedTransitionCount() {
        return transitions.size() - resolvedTransitionCount();
    }
}
