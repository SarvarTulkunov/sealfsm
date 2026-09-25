package io.sealfsm.serialize;

import io.sealfsm.model.Candidate;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The whole result of one run as JSON, for scoring against labels made BEFORE
 * the run (thesis Decision 3, {@code evaluation/PROTOCOL.md}).
 *
 * <p>The DOT and SCXML files describe one machine each and say nothing about the
 * roots the tool rejected, so a confusion matrix cannot be built from them. This
 * file carries every examined root's outcome, every machine at the three state
 * levels Decision 2 separates, every transition with its resolved flag, and every
 * candidate marked provisional. A scorer can then count what Decision 3 asks for:
 * classification outcomes per root, state-set accuracy, transition precision and
 * recall, and unresolved edges as their own figure.
 *
 * <p>Hand-written, because the tool has no runtime dependency beyond Spoon. The
 * schema is versioned so a scorer can refuse a file it does not understand.
 */
public final class JsonResultSerializer {

    /** Bumped on any incompatible change to the layout below. */
    public static final int SCHEMA_VERSION = 1;

    public String serialize(ExtractionResult result) {
        StringBuilder b = new StringBuilder();
        b.append("{\n");
        field(b, 1, "tool", str("sealfsm")).append(",\n");
        field(b, 1, "schema", String.valueOf(SCHEMA_VERSION)).append(",\n");

        List<String> outcomes = new ArrayList<>();
        for (ExtractionResult.RootOutcome o : result.outcomes()) {
            outcomes.add(obj(2, List.of(
                    kv("root", str(o.qualifiedName())),
                    kv("outcome", str(o.outcome().name())),
                    kv("reason", str(o.reason())))));
        }
        field(b, 1, "outcomes", arr(1, outcomes)).append(",\n");

        List<String> machines = new ArrayList<>();
        for (StateMachine m : result.machines()) machines.add(machine(m));
        field(b, 1, "machines", arr(1, machines)).append(",\n");

        List<String> candidates = new ArrayList<>();
        for (Candidate c : result.candidates()) candidates.add(candidate(c));
        field(b, 1, "candidates", arr(1, candidates)).append(",\n");

        List<String> diagnostics = new ArrayList<>();
        for (ExtractionResult.Diagnostic d : result.diagnostics()) {
            diagnostics.add(obj(2, List.of(
                    kv("severity", str(d.severity().name())),
                    kv("where", str(d.where())),
                    kv("message", str(d.message())))));
        }
        field(b, 1, "diagnostics", arr(1, diagnostics)).append("\n");
        b.append("}\n");
        return b.toString();
    }

    private String machine(StateMachine m) {
        List<String> transitions = new ArrayList<>();
        for (Transition t : m.transitions()) {
            transitions.add(obj(4, List.of(
                    kv("from", str(t.from())),
                    kv("to", str(t.to())),
                    kv("event", str(t.event())),
                    kv("guard", str(t.guard())),
                    kv("resolved", String.valueOf(t.isResolved())),
                    kv("otherwise", String.valueOf(t.isOtherwise())),
                    kv("successorForm", str(t.form() == null ? null : t.form().name())),
                    kv("note", str(t.note())))));
        }
        return obj(2, List.of(
                kv("root", str(m.qualifiedName())),
                kv("name", str(m.name())),
                kv("encoding", str(m.encoding().name())),
                kv("commitEvidence", str(m.commitEvidence().name())),
                kv("commitForms", strings(m.commitForms().stream().map(Enum::name).sorted().toList())),
                kv("successorForms", strings(m.successorForms().stream().map(Enum::name).sorted().toList())),
                kv("initialState", str(m.initialState().orElse(null))),
                kv("alphabet", strings(m.alphabet())),
                kv("directBranches", states(m.directBranches())),
                kv("atomicStates", states(m.atomicStates())),
                kv("compositeNodes", states(m.compositeNodes())),
                kv("resolvedTransitions", String.valueOf(m.resolvedTransitionCount())),
                kv("unresolvedTransitions", String.valueOf(m.unresolvedTransitionCount())),
                kv("transitions", arr(3, transitions))));
    }

    private String candidate(Candidate c) {
        return obj(2, List.of(
                kv("root", str(c.qualifiedName())),
                kv("name", str(c.name())),
                kv("provisional", String.valueOf(c.isProvisional())),
                kv("basis", strings(c.basis().stream().map(Enum::name).toList())),
                kv("reason", str(c.reason())),
                kv("sites", strings(c.dispatchSites())),
                kv("provisionalDirectBranches", states(c.directBranches())),
                kv("provisionalAtomicMembers", states(c.atomicStates()))));
    }

    private String states(List<State> states) {
        return "[" + states.stream().map(s -> "{" + String.join(", ", List.of(
                "\"id\": " + str(s.id()),
                "\"qualifiedName\": " + str(s.qualifiedName()),
                "\"origin\": " + str(s.origin().name()),
                "\"grouping\": " + s.isGrouping(),
                "\"openBranch\": " + s.isOpenBranch(),
                "\"initial\": " + s.isInitial(),
                "\"terminal\": " + s.isTerminal(),
                "\"declarationUnread\": " + s.isDeclarationUnread())) + "}")
                .collect(Collectors.joining(", ")) + "]";
    }

    // ---- minimal JSON writing ----------------------------------------------

    private static String kv(String key, String value) {
        return str(key) + ": " + value;
    }

    private static StringBuilder field(StringBuilder b, int depth, String key, String value) {
        return b.append(indent(depth)).append(kv(key, value));
    }

    private static String obj(int depth, List<String> members) {
        String inner = indent(depth + 1);
        return "{\n" + members.stream().map(m -> inner + m).collect(Collectors.joining(",\n"))
                + "\n" + indent(depth) + "}";
    }

    private static String arr(int depth, List<String> items) {
        if (items.isEmpty()) return "[]";
        String inner = indent(depth + 1);
        return "[\n" + items.stream().map(i -> inner + i).collect(Collectors.joining(",\n"))
                + "\n" + indent(depth) + "]";
    }

    private static String strings(Collection<String> values) {
        return "[" + values.stream().map(JsonResultSerializer::str).collect(Collectors.joining(", ")) + "]";
    }

    private static String indent(int depth) {
        return "  ".repeat(depth);
    }

    /** A JSON string literal, or {@code null}. */
    static String str(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }
}
