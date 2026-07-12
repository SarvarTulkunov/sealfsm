package io.sealfsm.serialize;

import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;

/**
 * Serializes a {@link StateMachine} to Graphviz DOT. This is the primary
 * debugging aid: it is trivial to render ({@code dot -Tpng fsm.dot -o fsm.png})
 * and exposes the extracted structure visually from day one.
 *
 * <p>Composite (super) states are drawn as {@code subgraph cluster_*} boxes.
 * The initial state is marked with an entry arrow from a hidden point. Any
 * unresolved transition is drawn as a dashed red edge into a shared {@code ?}
 * sink, so gaps in transition recovery are immediately obvious on the diagram.
 */
public final class DotSerializer {

    public String serialize(StateMachine m) {
        StringBuilder sb = new StringBuilder();
        sb.append("digraph ").append(q(m.name())).append(" {\n");
        sb.append("  rankdir=LR;\n");
        sb.append("  labelloc=\"t\";\n");
        sb.append("  label=").append(q(m.name() + "  (" + m.encoding() + ")")).append(";\n");
        sb.append("  node [shape=rectangle, style=rounded, fontname=\"Helvetica\"];\n");
        sb.append("  edge [fontname=\"Helvetica\", fontsize=10];\n\n");

        // Entry arrow into the initial state.
        m.initialState().ifPresent(init -> {
            sb.append("  __start [shape=point, width=0.12, label=\"\"];\n");
            sb.append("  __start -> ").append(q(init)).append(";\n\n");
        });

        boolean hasUnresolved = m.transitions().stream().anyMatch(t -> !t.isResolved());
        if (hasUnresolved) {
            sb.append("  \"?\" [shape=none, label=\"?\", fontcolor=\"#b00020\"];\n\n");
        }

        for (State s : m.topLevelStates()) {
            emitState(s, sb, "  ");
        }
        sb.append('\n');

        for (Transition t : m.transitions()) {
            emitTransition(t, sb);
        }

        sb.append("}\n");
        return sb.toString();
    }

    private void emitState(State s, StringBuilder sb, String indent) {
        if (s.isComposite() && !s.children().isEmpty()) {
            sb.append(indent).append("subgraph cluster_").append(safe(s.id())).append(" {\n");
            sb.append(indent).append("  label=").append(q(s.id())).append(";\n");
            sb.append(indent).append("  style=rounded; color=\"#888888\";\n");
            for (State child : s.children()) {
                emitState(child, sb, indent + "  ");
            }
            sb.append(indent).append("}\n");
        } else {
            sb.append(indent).append(q(s.id()));
            if (s.isInitial()) {
                sb.append(" [penwidth=2, color=\"#1a73e8\"]");
            }
            sb.append(";\n");
        }
    }

    private void emitTransition(Transition t, StringBuilder sb) {
        String label = edgeLabel(t);
        if (t.isResolved()) {
            sb.append("  ").append(q(t.from())).append(" -> ").append(q(t.to()));
            if (!label.isEmpty()) sb.append(" [label=").append(q(label)).append("]");
            sb.append(";\n");
        } else {
            sb.append("  ").append(q(t.from())).append(" -> \"?\" [style=dashed, color=\"#b00020\"");
            String l = label.isEmpty() ? "unresolved" : label + " (unresolved)";
            sb.append(", label=").append(q(l)).append("];\n");
        }
    }

    private static String edgeLabel(Transition t) {
        StringBuilder sb = new StringBuilder();
        if (t.event() != null) sb.append(t.event());
        if (t.guard() != null) sb.append(" [").append(t.guard()).append(']');
        return sb.toString().trim();
    }

    /** Quote and escape a DOT string literal. */
    private static String q(String raw) {
        return '"' + raw.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    /** Sanitize an identifier for use in a cluster name. */
    private static String safe(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
