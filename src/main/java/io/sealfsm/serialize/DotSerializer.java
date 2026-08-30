package io.sealfsm.serialize;

import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Serializes a {@link StateMachine} to Graphviz DOT. This is the primary
 * debugging aid: it is trivial to render ({@code dot -Tpng fsm.dot -o fsm.png})
 * and exposes the extracted structure visually from day one.
 *
 * <p>Composite (super) states are drawn as {@code subgraph cluster_*} boxes.
 * The initial state is marked with an entry arrow from a hidden point. Any
 * unresolved transition is drawn as a dashed red edge into a shared {@code ?}
 * sink, so gaps in transition recovery are immediately obvious on the diagram.
 *
 * <p>Pseudo-states are declared explicitly rather than left to Graphviz. A node
 * that only ever appears as an edge endpoint is auto-created with the file's
 * default {@code shape=rectangle, style=rounded}, so an undetermined source
 * ({@code <unknown>}) would render as a rounded box indistinguishable from a
 * real state — a recall gap dressed as a result, which is the one thing the
 * unresolved-transition invariant exists to prevent.
 */
public final class DotSerializer {

    public String serialize(StateMachine m) {
        // A composite state is drawn as a cluster, and a cluster is not a node:
        // naming it in an edge makes Graphviz silently invent a SECOND, unrelated
        // node with the same label, so the rendered diagram shows the state twice.
        // Edges touching a composite are therefore routed through an invisible
        // anchor inside its cluster and clipped to the cluster boundary with
        // lhead/ltail (which require compound=true).
        // For each composite: itself plus every descendant. Clipping an edge to a
        // cluster boundary only makes sense when the other endpoint is OUTSIDE that
        // cluster; Graphviz warns and ignores lhead/ltail otherwise (a composite
        // self-loop, or an edge from the composite down into one of its children).
        Map<String, Set<String>> composites = new LinkedHashMap<>();
        for (State s : m.allStates()) {
            if (s.isComposite() && !s.children().isEmpty()) {
                Set<String> members = new LinkedHashSet<>();
                collectMembers(s, members);
                composites.put(s.id(), members);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("digraph ").append(q(m.name())).append(" {\n");
        sb.append("  rankdir=LR;\n");
        if (!composites.isEmpty()) sb.append("  compound=true;\n");
        sb.append("  labelloc=\"t\";\n");
        sb.append("  label=").append(q(m.name() + "  (" + m.encoding() + ")")).append(";\n");
        sb.append("  node [shape=rectangle, style=rounded, fontname=\"Helvetica\"];\n");
        sb.append("  edge [fontname=\"Helvetica\", fontsize=10];\n\n");

        Set<String> realIds = new LinkedHashSet<>();
        for (State s : m.allStates()) realIds.add(s.id());

        // Entry arrow into the initial state. When the "initial state" is itself
        // the entry pseudo-state, it is already drawn as an entry marker below and
        // a second point node aimed at it would just be a dot pointing at a dot.
        m.initialState()
                .filter(init -> !StateMachine.INITIAL_PSEUDO_STATE.equals(init))
                .ifPresent(init -> {
                    sb.append("  __start [shape=point, width=0.12, label=\"\"];\n");
                    sb.append("  __start -> ").append(q(endpoint(init, composites)));
                    if (clipsTo(init, "__start", composites)) {
                        sb.append(" [lhead=").append(cluster(init)).append("]");
                    }
                    sb.append(";\n\n");
                });

        boolean hasUnresolved = m.transitions().stream().anyMatch(t -> !t.isResolved());
        if (hasUnresolved) {
            sb.append("  \"?\" [shape=none, label=\"?\", fontcolor=\"#b00020\"];\n\n");
        }

        // Pseudo-states — machine entry, or an edge whose source the analysis could
        // not determine — are named by an edge but are NOT permitted subtypes, so
        // nothing above declares them. Graphviz auto-creates any node an edge names
        // and applies the file's default `shape=rectangle, style=rounded`, which
        // renders a gap as a box indistinguishable from a real state (`<unknown>`
        // read as an eleventh LCP state). Declare them explicitly instead.
        List<String> pseudo = pseudoEndpoints(m, realIds);
        if (!pseudo.isEmpty()) {
            for (String id : pseudo) {
                sb.append("  ").append(q(id)).append(' ').append(pseudoAttrs(id)).append(";\n");
            }
            sb.append('\n');
        }

        for (State s : m.topLevelStates()) {
            emitState(s, sb, "  ");
        }
        sb.append('\n');

        for (Transition t : m.transitions()) {
            emitTransition(t, sb, composites);
        }

        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Edge endpoints that are not permitted subtypes, in first-seen order. The
     * predicate is "absent from {@link StateMachine#allStates()}", the same one
     * {@code ScxmlSerializer.emitPseudoStates} uses — a serializer that enumerated
     * the pseudo-state spellings itself would fall out of step with the extractor
     * the first time a new one is introduced.
     */
    private static List<String> pseudoEndpoints(StateMachine m, Set<String> realIds) {
        Set<String> out = new LinkedHashSet<>();
        for (Transition t : m.transitions()) {
            if (t.from() != null && !realIds.contains(t.from())) out.add(t.from());
            if (t.isResolved() && t.to() != null && !realIds.contains(t.to())) out.add(t.to());
        }
        return new ArrayList<>(out);
    }

    /**
     * How a pseudo-state is drawn. Machine entry is a real, proven start point and
     * gets the conventional filled dot — the same vocabulary as {@code __start}. An
     * undetermined source is a <em>gap</em>, so it is styled exactly like the
     * {@code ?} target sink: a bare red marker, never a node shape, so that the two
     * halves of one unrecovered edge read alike.
     */
    private static String pseudoAttrs(String id) {
        if (StateMachine.INITIAL_PSEUDO_STATE.equals(id)) {
            return "[shape=point, width=0.12, label=\"\"]";
        }
        return "[shape=none, fontcolor=\"#b00020\"]";
    }

    private static void collectMembers(State s, Set<String> out) {
        out.add(s.id());
        for (State c : s.children()) collectMembers(c, out);
    }

    /** The node an edge should actually attach to: a composite's anchor, else the state itself. */
    private static String endpoint(String stateId, Map<String, Set<String>> composites) {
        return composites.containsKey(stateId) ? anchor(stateId) : stateId;
    }

    /**
     * Should an edge endpoint be clipped to the cluster of {@code stateId}? Only
     * when {@code stateId} is a composite and the edge's other end lies outside it.
     */
    private static boolean clipsTo(String stateId, String otherEnd, Map<String, Set<String>> composites) {
        Set<String> members = composites.get(stateId);
        return members != null && !members.contains(otherEnd);
    }

    private static String anchor(String stateId) {
        return "__anchor_" + safe(stateId);
    }

    private static String cluster(String stateId) {
        return "cluster_" + safe(stateId);
    }

    private void emitState(State s, StringBuilder sb, String indent) {
        if (s.isComposite() && !s.children().isEmpty()) {
            sb.append(indent).append("subgraph ").append(cluster(s.id())).append(" {\n");
            sb.append(indent).append("  label=").append(q(s.id())).append(";\n");
            sb.append(indent).append("  style=rounded; color=\"#888888\";\n");
            // Invisible attachment point for edges that enter or leave the whole
            // composite; without it those edges would fabricate a duplicate node.
            sb.append(indent).append("  ").append(q(anchor(s.id())))
              .append(" [shape=point, style=invis, width=0.01, label=\"\"];\n");
            for (State child : s.children()) {
                emitState(child, sb, indent + "  ");
            }
            sb.append(indent).append("}\n");
        } else {
            sb.append(indent).append(q(s.id()));
            List<String> attrs = new ArrayList<>();
            if (s.isInitial()) {
                attrs.add("penwidth=2");
                // The colour is yielded to the unread-declaration rule below when
                // both apply: a state can be the machine's start AND rest on a
                // guessed name, and emitting `color` twice leaves Graphviz to pick
                // (it takes the last) rather than the serializer saying which
                // signal matters. The thicker border still shows the initial state.
                if (!s.isDeclarationUnread()) attrs.add("color=\"#1a73e8\"");
            }
            if (s.isTerminal()) {
                // Double border, the conventional accepting/terminal notation: the
                // dispatch matched this state and every path out of it throws.
                attrs.add("peripheries=2");
            }
            if (s.isDeclarationUnread()) {
                // Dotted, not dashed: dashed already means "unresolved edge" in this
                // file and the two are different claims. The state is certain — the
                // permits clause is compiler-checked — while its identity, and so
                // every edge matched to it, rests on a guessed qualified name. Last
                // in the list so its colour wins over `initial`'s while that rule's
                // penwidth still shows, since a state can be both.
                attrs.add("style=\"rounded,dotted\"");
                attrs.add("color=\"#b8860b\"");
            }
            if (!attrs.isEmpty()) sb.append(" [").append(String.join(", ", attrs)).append("]");
            sb.append(";\n");
        }
    }

    private void emitTransition(Transition t, StringBuilder sb, Map<String, Set<String>> composites) {
        String label = edgeLabel(t);
        String otherEnd = t.isResolved() ? t.to() : "?";
        List<String> attrs = new ArrayList<>();
        if (clipsTo(t.from(), otherEnd, composites)) attrs.add("ltail=" + cluster(t.from()));

        if (t.isResolved()) {
            if (clipsTo(t.to(), t.from(), composites)) attrs.add("lhead=" + cluster(t.to()));
            if (!label.isEmpty()) attrs.add("label=" + q(label));
            sb.append("  ").append(q(endpoint(t.from(), composites)))
              .append(" -> ").append(q(endpoint(t.to(), composites)));
        } else {
            attrs.add("style=dashed");
            attrs.add("color=\"#b00020\"");
            attrs.add("label=" + q(label.isEmpty() ? "unresolved" : label + " (unresolved)"));
            sb.append("  ").append(q(endpoint(t.from(), composites))).append(" -> \"?\"");
        }
        if (!attrs.isEmpty()) sb.append(" [").append(String.join(", ", attrs)).append("]");
        sb.append(";\n");
    }

    private static String edgeLabel(Transition t) {
        StringBuilder sb = new StringBuilder();
        if (t.event() != null) {
            sb.append(t.event());
        } else if (t.isOtherwise()) {
            // The default edge: it fires when no labelled transition does. Naming it
            // distinguishes "fires on anything else" from "no event was recovered".
            sb.append("otherwise");
        }
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
