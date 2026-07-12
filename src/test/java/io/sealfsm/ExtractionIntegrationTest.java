package io.sealfsm;

import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end extraction over the bundled example fixtures. Requires Spoon on the
 * classpath, so this runs on a normal build machine (it cannot run in a
 * Maven-Central-firewalled sandbox).
 */
class ExtractionIntegrationTest {

    private CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    private StateMachine single(ExtractionResult r) {
        assertEquals(1, r.machines().size(), "expected exactly one machine");
        return r.machines().get(0);
    }

    private boolean hasResolved(StateMachine m, String from, String to) {
        return m.transitions().stream()
                .anyMatch(t -> t.isResolved() && t.from().equals(from) && to.equals(t.to()));
    }

    private Set<String> stateIds(StateMachine m) {
        return m.allStates().stream().map(io.sealfsm.model.State::id).collect(Collectors.toSet());
    }

    // ---- distributed -------------------------------------------------------

    @Test
    void trafficLightDistributed() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/traffic"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.DISTRIBUTED, m.encoding());
        assertEquals(Set.of("Red", "Green", "Yellow"), stateIds(m));
        assertTrue(hasResolved(m, "Red", "Green"));
        assertTrue(hasResolved(m, "Green", "Yellow"));
        assertTrue(hasResolved(m, "Yellow", "Red"));
        assertEquals("Red", m.initialState().orElse(null));
        // State enumeration is the provably-complete part: nothing unresolved here.
        assertEquals(0, m.unresolvedTransitionCount());
    }

    // ---- centralized -------------------------------------------------------

    @Test
    void doorCentralizedWithGuardsAndSelfLoop() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/door"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED, m.encoding());
        assertEquals(Set.of("Open", "Closed", "Locked"), stateIds(m));

        assertTrue(hasResolved(m, "Open", "Closed"), "Open -> Closed");
        assertTrue(hasResolved(m, "Closed", "Locked"), "Closed -> Locked (guarded)");
        assertTrue(hasResolved(m, "Closed", "Open"), "Closed -> Open (else branch)");
        assertTrue(hasResolved(m, "Locked", "Closed"), "Locked -> Closed (guarded)");
        assertTrue(hasResolved(m, "Locked", "Locked"), "Locked -> Locked (return current self-loop)");

        // The guarded branch must carry a non-null guard.
        Transition guarded = m.transitions().stream()
                .filter(t -> t.from().equals("Closed") && "Locked".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(guarded.guard(), "guarded transition should record its condition");

        assertEquals("Closed", m.initialState().orElse(null));
    }

    // ---- negative control --------------------------------------------------

    @Test
    void shapeSumTypeIsRejected() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/shape"));
        assertTrue(r.isEmpty(), "a plain sum type must not be classified as an FSM");
        boolean explained = r.diagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("sum type"));
        assertTrue(explained, "rejection reason should be reported");
    }

    // ---- combined ----------------------------------------------------------

    @Test
    void allExamplesTogether() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples"));
        List<String> names = r.machines().stream().map(StateMachine::name).toList();
        assertTrue(names.contains("TrafficLight"));
        assertTrue(names.contains("Door"));
        assertFalse(names.contains("Shape"), "Shape must be excluded");
    }
}
