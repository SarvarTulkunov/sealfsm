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

    private final List<StateMachine> machines = new ArrayList<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public void addMachine(StateMachine m) { machines.add(m); }
    public void info(String where, String message) {
        diagnostics.add(new Diagnostic(Severity.INFO, where, message));
    }
    public void warn(String where, String message) {
        diagnostics.add(new Diagnostic(Severity.WARN, where, message));
    }

    public List<StateMachine> machines()    { return Collections.unmodifiableList(machines); }
    public List<Diagnostic> diagnostics()   { return Collections.unmodifiableList(diagnostics); }

    public boolean isEmpty() { return machines.isEmpty(); }
}
