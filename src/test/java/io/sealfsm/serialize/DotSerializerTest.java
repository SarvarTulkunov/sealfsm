package io.sealfsm.serialize;

import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure (Spoon-independent) checks on DOT emission. These mirror the behaviour
 * verified by hand during development and run in any environment.
 */
class DotSerializerTest {

    private StateMachine sample() {
        StateMachine m = new StateMachine("TrafficLight",
                "examples.traffic.TrafficLight", StateMachine.Encoding.POLYMORPHIC);
        m.addTopLevelState(new State("Red", "examples.traffic.Red", false));
        m.addTopLevelState(new State("Green", "examples.traffic.Green", false));
        m.addTopLevelState(new State("Yellow", "examples.traffic.Yellow", false));
        m.addTransition(Transition.resolved("Red", "Green", "next", null));
        m.addTransition(Transition.resolved("Green", "Yellow", "next", null));
        m.addTransition(Transition.resolved("Yellow", "Red", "next", null));
        m.addTransition(Transition.unresolved("Yellow", "fault", null, "computeFault()"));
        m.setInitialState("Red");
        return m;
    }

    @Test
    void emitsDigraphWithStatesAndEdges() {
        String dot = new DotSerializer().serialize(sample());
        assertTrue(dot.startsWith("digraph"));
        assertTrue(dot.contains("\"Red\" -> \"Green\""));
        assertTrue(dot.contains("__start -> \"Red\""), "initial entry arrow expected");
    }

    @Test
    void unresolvedEdgeIsDashedIntoSink() {
        String dot = new DotSerializer().serialize(sample());
        assertTrue(dot.contains("\"?\""), "unresolved sink node expected");
        assertTrue(dot.contains("style=dashed"));
    }

    @Test
    void initialStateIsHighlighted() {
        String dot = new DotSerializer().serialize(sample());
        assertTrue(dot.contains("\"Red\" [penwidth=2"));
        assertFalse(dot.contains("\"Green\" [penwidth=2"));
    }

    @Test
    void terminalStateGetsADoubleBorder() {
        // A state the dispatch matched but from which every path rejects is
        // absorbing, and the conventional automaton notation for that is a double
        // border. Marking it is a positive claim, so the serializer must only
        // reflect the flag, never re-derive it from "has no outgoing edge here".
        StateMachine m = sample();
        m.markTerminalStates(java.util.Set.of("Red", "Green", "Yellow"));
        String dot = new DotSerializer().serialize(m);
        assertFalse(dot.contains("peripheries=2"),
                "every state has an outbound edge, so none is terminal");

        StateMachine absorbing = new StateMachine("Latch",
                "examples.barefield.Latch", StateMachine.Encoding.CENTRALIZED_DISPATCH);
        absorbing.addTopLevelState(new State("Idle", "examples.barefield.Idle", false));
        absorbing.addTopLevelState(new State("Fired", "examples.barefield.Fired", false));
        absorbing.addTransition(Transition.resolved("Idle", "Fired", "TRIGGER", null));
        absorbing.markTerminalStates(java.util.Set.of("Idle", "Fired"));
        String out = new DotSerializer().serialize(absorbing);
        assertTrue(out.contains("\"Fired\" [peripheries=2]"), "the absorbing state is doubled");
        assertFalse(out.contains("\"Idle\" [peripheries=2]"), "a state with an exit is not terminal");
    }

    @Test
    void aStateTheDispatchNeverMatchedIsNotCalledTerminal() {
        // The distinction that keeps "terminal" honest: zero outbound edges means
        // "absorbing" only when the analysis actually looked. A state no arm
        // matched has no edges because none were recovered, and reporting that as
        // terminal would dress a recall gap as a result.
        StateMachine m = new StateMachine("Latch",
                "examples.barefield.Latch", StateMachine.Encoding.CENTRALIZED_DISPATCH);
        m.addTopLevelState(new State("Idle", "examples.barefield.Idle", false));
        m.addTopLevelState(new State("Unseen", "examples.barefield.Unseen", false));
        m.addTransition(Transition.resolved("Idle", "Idle", "RESET", null));
        m.markTerminalStates(java.util.Set.of("Idle"));   // Unseen was never dispatched
        assertFalse(new DotSerializer().serialize(m).contains("peripheries=2"),
                "an unvisited state must not be reported as terminal");
    }

    // ---- pseudo-states ------------------------------------------------------

    @Test
    void anUndeterminedSourceIsAMarkerNotAStateBox() {
        // Regression (LCP): `<unknown>` appeared only as an edge endpoint, so
        // Graphviz auto-created it and applied the file's default
        // `shape=rectangle, style=rounded`. The rendered diagram then showed a
        // rounded box captioned "<unknown>" sitting among the real states, reading
        // as an eleventh state — a recall gap dressed as a result. It must be
        // declared, and styled like the `?` sink it points at.
        StateMachine m = sample();
        m.addTransition(Transition.unresolved("<unknown>", null, null, "requireNonNull(event, ...)"));
        String dot = new DotSerializer().serialize(m);

        assertTrue(dot.contains("\"<unknown>\" [shape=none, fontcolor=\"#b00020\"];"),
                "the undetermined source must be declared as a red marker");
        // Declared before the edges, so nothing has auto-created it with the
        // default node shape by the time it is first named.
        assertTrue(dot.indexOf("\"<unknown>\" [shape=none") < dot.indexOf("\"<unknown>\" ->"),
                "the declaration must precede the edge that names it");
    }

    @Test
    void realStatesAreNotTreatedAsPseudoStates() {
        // The predicate is "absent from allStates()", so a composite's CHILD — a
        // real state that is never declared at top level — must not be swept up
        // and restyled as a gap marker.
        String dot = new DotSerializer().serialize(compositeSample());
        assertFalse(dot.contains("\"RAMP\" [shape=none"), "a child state is a state");
        assertFalse(dot.contains("[shape=none, fontcolor=\"#b00020\"];"),
                "a fully resolved machine declares no gap markers");
    }

    @Test
    void machineEntryPseudoStateIsADotNotADuplicateStartPoint() {
        // When the analyzer could not prove which permitted subtype starts the
        // machine it sources those edges at <initial>. That is a real entry point,
        // so it gets the conventional filled dot — and the separate __start point
        // is suppressed, since a dot aimed at a dot says nothing.
        StateMachine m = new StateMachine("CancellationState",
                "cancellation.CancellationState", StateMachine.Encoding.CENTRALIZED_DISPATCH);
        m.addTopLevelState(new State("Pending", "cancellation.Pending", false));
        m.addTopLevelState(new State("Cancelled", "cancellation.Cancelled", false));
        m.addTransition(Transition.resolved(StateMachine.INITIAL_PSEUDO_STATE, "Pending", "subscribe", null));
        m.setInitialState(StateMachine.INITIAL_PSEUDO_STATE);
        String dot = new DotSerializer().serialize(m);

        assertTrue(dot.contains("\"<initial>\" [shape=point, width=0.12, label=\"\"];"),
                "machine entry is drawn as a point, not a rounded box");
        assertFalse(dot.contains("\"<initial>\" [shape=none"),
                "entry is a proven start point, not an unresolved gap");
        assertFalse(dot.contains("__start"), "no second entry point aimed at the entry point");
    }

    // ---- composite states ---------------------------------------------------

    /** A machine with a composite state that edges enter, leave and loop on. */
    private StateMachine compositeSample() {
        StateMachine m = new StateMachine("Signal",
                "valueforms.Signal", StateMachine.Encoding.POLYMORPHIC);
        m.addTopLevelState(new State("Idle", "valueforms.Idle", false));
        State phase = new State("Phase", "valueforms.Phase", true);
        phase.addChild(new State("RAMP", "valueforms.Phase.RAMP", false));
        phase.addChild(new State("PEAK", "valueforms.Phase.PEAK", false));
        m.addTopLevelState(phase);
        m.addTransition(Transition.resolved("Idle", "RAMP", "ADVANCE", null));  // enters a child
        m.addTransition(Transition.resolved("Idle", "Phase", "ENTER", null));   // enters the composite
        m.addTransition(Transition.resolved("Phase", "Idle", "RESET", null));   // leaves the composite
        m.addTransition(Transition.resolved("Phase", "PEAK", "STEP", null));    // down into a child
        m.addTransition(Transition.resolved("Phase", "Phase", null, "else").asOtherwise()); // loops on it
        m.setInitialState("Idle");
        return m;
    }

    /** The emitted line for the edge carrying {@code marker}. */
    private String edgeLine(String dot, String marker) {
        return dot.lines().filter(l -> l.contains("->") && l.contains(marker))
                .findFirst().orElseThrow(() -> new AssertionError("no edge containing " + marker));
    }

    @Test
    void compositeEndpointsDoNotFabricateADuplicateNode() {
        // Regression: a composite is drawn as `subgraph cluster_X`, and a cluster
        // is not a node. Naming it in an edge made Graphviz silently invent a
        // second node with the same label, so the rendered diagram showed the
        // state twice — once as the box, once as a stray node beside it. Edges
        // must attach to an anchor inside the cluster instead.
        String dot = new DotSerializer().serialize(compositeSample());
        assertTrue(dot.contains("subgraph cluster_Phase"), "composite is a cluster");
        assertFalse(dot.contains("\"Phase\" ->"), "no edge may name the cluster as a node");
        assertFalse(dot.contains("-> \"Phase\""), "no edge may name the cluster as a node");
        assertTrue(dot.contains("__anchor_Phase"), "edges attach to the cluster's anchor");
        assertTrue(dot.contains("compound=true"), "lhead/ltail clipping needs compound mode");
    }

    @Test
    void edgesTouchingACompositeAreClippedToItsBoundary() {
        String dot = new DotSerializer().serialize(compositeSample());
        // Crossing the boundary from outside clips at the crossing end.
        assertTrue(edgeLine(dot, "RESET").contains("ltail=cluster_Phase"),
                "an edge leaving the composite clips to it");
        assertTrue(edgeLine(dot, "ENTER").contains("lhead=cluster_Phase"),
                "an edge entering the composite clips to it");

        // A child is a plain node, so entering RAMP directly must NOT be clipped —
        // `Idle -> RAMP` names a real state and needs no anchor indirection.
        assertTrue(dot.contains("\"Idle\" -> \"RAMP\""), "entering a child targets the child");
        assertFalse(edgeLine(dot, "ADVANCE").contains("__anchor_Phase"),
                "a child target must not be redirected to the parent's anchor");
    }

    @Test
    void clippingIsSuppressedWhenBothEndsAreInsideTheSameCluster() {
        // Graphviz cannot clip an edge to a boundary it never crosses: it warns
        // "tail is inside head cluster" and drops the attribute. That applies to a
        // composite's self-loop and to an edge from a composite down into one of
        // its own children, so neither may carry lhead/ltail.
        String dot = new DotSerializer().serialize(compositeSample());

        String selfLoop = edgeLine(dot, "otherwise");
        assertFalse(selfLoop.contains("ltail="), "a composite self-loop crosses no boundary");
        assertFalse(selfLoop.contains("lhead="), "a composite self-loop crosses no boundary");

        String intoChild = edgeLine(dot, "STEP");
        assertFalse(intoChild.contains("ltail="), "descending into an own child crosses no boundary");
    }

    @Test
    void leafOnlyMachinesAreUnchangedByCompositeHandling() {
        // The anchor/compound machinery must not touch a flat machine — every
        // existing reference output depends on that.
        String dot = new DotSerializer().serialize(sample());
        assertFalse(dot.contains("compound=true"));
        assertFalse(dot.contains("__anchor_"));
    }
}
