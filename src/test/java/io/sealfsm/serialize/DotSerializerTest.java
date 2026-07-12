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
}
