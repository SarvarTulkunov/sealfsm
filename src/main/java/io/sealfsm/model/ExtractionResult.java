package io.sealfsm.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of running the analyzer over a source tree: every state machine
 * found, plus human-readable diagnostics (rejected sealed types, unresolved
 * transitions, missing initial states, etc.). Diagnostics are first-class
 * because honest reporting of what could <em>not</em> be resolved is part of
 * the thesis's validity argument.
 */
public final class ExtractionResult {

    public enum Severity { INFO, WARN }

    public record Diagnostic(Severity severity, String where, String message) {
        @Override
        public String toString() {
            return "[" + severity + "] " + where + ": " + message;
        }
    }

    /**
     * Why one sealed root was rejected, predicate by predicate ({@code --explain}).
     *
     * <p>Not a {@link Diagnostic}: a diagnostic reports something the analysis
     * found, and is suppressed by {@code --quiet} on that basis. This is the
     * decision procedure narrating itself on request, so it is carried on its own
     * channel and printed whenever it was asked for.
     */
    public record Explanation(String where, List<String> predicates) { }

    /**
     * How one machine's inter-procedural values were bound ({@code --explain}):
     * for each edge resolved through a parameter or receiver binding, the chain of
     * hops it travelled from the dispatch inward; for each call the fold declined
     * and each binding it refused, the rule that stopped it. The same kind of
     * channel as {@link Explanation} — the decision procedure narrating itself on
     * request — kept apart from it because it narrates a different procedure.
     */
    public record BindingTrace(String where, List<String> lines) { }

    private final List<StateMachine> machines = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<Explanation> explanations = new ArrayList<>();
    private final List<BindingTrace> bindingTraces = new ArrayList<>();

    public void addMachine(StateMachine m) { machines.add(m); }
    public void addCandidate(Candidate c) { candidates.add(c); }
    public void info(String where, String message) {
        diagnostics.add(new Diagnostic(Severity.INFO, where, message));
    }
    public void warn(String where, String message) {
        diagnostics.add(new Diagnostic(Severity.WARN, where, message));
    }

    public void explain(String where, List<String> predicates) {
        explanations.add(new Explanation(where, List.copyOf(predicates)));
    }

    public void traceBindings(String where, List<String> lines) {
        bindingTraces.add(new BindingTrace(where, List.copyOf(lines)));
    }

    public List<StateMachine> machines()    { return Collections.unmodifiableList(machines); }

    /**
     * Hierarchies the tool refuses to call machines and whose states it reports
     * anyway — see {@link Candidate}.
     *
     * <p>A separate channel from {@link #machines()}, not a flag on one list. A
     * candidate carries no transition relation and no serialized output, so a
     * caller that iterates machines must not see it; and the state set is the whole
     * point of reporting it, so it must not be reduced to a diagnostic string
     * either. Keeping the two lists apart is what lets "how many machines did the
     * tool find" and "for how many hierarchies can it name the states" be two
     * numbers instead of one.
     */
    public List<Candidate> candidates()     { return Collections.unmodifiableList(candidates); }
    public List<Diagnostic> diagnostics()   { return Collections.unmodifiableList(diagnostics); }
    public List<Explanation> explanations() { return Collections.unmodifiableList(explanations); }
    public List<BindingTrace> bindingTraces() { return Collections.unmodifiableList(bindingTraces); }

    /** True when no <em>machine</em> was found; candidates do not count as machines. */
    public boolean isEmpty() { return machines.isEmpty(); }
}
