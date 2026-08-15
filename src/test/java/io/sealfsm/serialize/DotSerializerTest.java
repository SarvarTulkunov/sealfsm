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
                "examples.traffic.TrafficLight", StateMachine.Encoding.DISTRIBUTED);
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

    // ---- composite states ---------------------------------------------------

    /** A machine with a composite state that edges enter, leave and loop on. */
    private StateMachine compositeSample() {
        StateMachine m = new StateMachine("Signal",
                "valueforms.Signal", StateMachine.Encoding.DISTRIBUTED);
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
