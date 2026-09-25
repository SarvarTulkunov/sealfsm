package io.sealfsm;

import io.sealfsm.extract.StateExtractor;
import io.sealfsm.model.Candidate;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.serialize.DotSerializer;
import io.sealfsm.serialize.ScxmlSerializer;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thesis Decision 2: direct permitted branches, grouping nodes and atomic states
 * are three different levels, and every count says which one it is.
 *
 * <p>The tool used to report one number: the flattened node list, in which a
 * permitted enum counted once as a state and once per constant. The decision's
 * own example is {@code sealed interface Phase permits Idle, Speed} with
 * {@code enum Speed { SLOW, FAST }}. It has direct branches {@code {Idle, Speed}}
 * and atomic states {@code {Idle, Speed.SLOW, Speed.FAST}}. {@code Speed} is a
 * grouping node, never counted again as an atomic state.
 *
 * <p>Two limits on the exact claim are tested as well, because the decision asks
 * that they be reported where they apply. A {@code non-sealed} branch is open,
 * so its subclasses are not states. A type reachable under two direct branches is
 * one state, and no branch is chosen for it.
 */
class StateLevelsTest {

    private static final String LEVELS = "src/test/resources/statelevels";

    private static CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    private static StateMachine machine(ExtractionResult r, String name) {
        return r.machines().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no machine " + name));
    }

    private static Set<String> ids(List<State> states) {
        return states.stream().map(State::id).collect(Collectors.toSet());
    }

    private static CtType<?> type(CtModel model, String qualifiedName) {
        return model.getAllTypes().stream().filter(t -> t.getQualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow(() -> new AssertionError("no type " + qualifiedName));
    }

    @Test
    void anEnumBranchIsAGroupingNodeAndItsConstantsAreTheAtomicStates() {
        StateMachine phase = machine(new Analyzer().analyze(modelOf(LEVELS)), "Phase");
        assertEquals(Set.of("Idle", "Speed"), ids(phase.directBranches()));
        assertEquals(Set.of("Idle", "SLOW", "FAST"), ids(phase.atomicStates()));
        assertEquals(Set.of("Speed"), ids(phase.compositeNodes()));
        // The structural view keeps every node; it is not a state count.
        assertEquals(4, phase.allStates().size());
        assertTrue(phase.atomicStates().stream().filter(s -> !s.id().equals("Idle"))
                .allMatch(s -> s.origin() == State.Origin.ENUM_CONSTANT));
    }

    @Test
    void aNestedSealedBranchExpandsWithoutJoiningTheDirectBranchCount() {
        StateMachine mode = machine(new Analyzer().analyze(modelOf(LEVELS)), "Mode");
        assertEquals(Set.of("Off", "On"), ids(mode.directBranches()));
        assertEquals(Set.of("Off", "Low", "High"), ids(mode.atomicStates()));
        assertEquals(Set.of("On"), ids(mode.compositeNodes()));
        // The exported machine keeps the expansion: the deeper nodes are drawn.
        String dot = new DotSerializer().serialize(mode);
        assertTrue(dot.contains("cluster_On") && dot.contains("\"Low\"") && dot.contains("\"High\""), dot);
    }

    @Test
    void anOpenBranchIsOneStateAndItsSubclassesAreReportedNotEnumerated() {
        CtModel model = modelOf(LEVELS);
        StateExtractor.Result result = new StateExtractor().extract(type(model, "statelevels.Hatch"), model);
        State ajar = result.topLevelStates().stream().filter(s -> s.id().equals("Ajar")).findFirst().orElseThrow();
        assertTrue(ajar.isOpenBranch());
        assertTrue(ajar.isAtomic(), "an open branch is one state; its subclasses are not children");
        assertEquals(Map.of("statelevels.Ajar", Set.of("statelevels.WideAjar")), result.openBranches());

        ExtractionResult r = new Analyzer().analyze(model);
        StateMachine hatch = machine(r, "Hatch");
        assertEquals(Set.of("Shut", "Ajar"), ids(hatch.atomicStates()));
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.severity() == ExtractionResult.Severity.WARN
                        && d.where().equals("statelevels.Hatch") && d.message().contains("non-sealed")
                        && d.message().contains("statelevels.WideAjar")),
                "the open branch and its subclass are reported where they affect the machine");
    }

    @Test
    void aTypeUnderTwoBranchesIsOneAtomicStateAndNoBranchIsChosen() {
        CtModel model = modelOf(LEVELS);
        StateExtractor.Result result = new StateExtractor().extract(type(model, "statelevels.Signal"), model);
        assertEquals(Map.of("statelevels.Blink", Set.of("statelevels.Amber", "statelevels.Red")),
                result.overlaps());

        ExtractionResult r = new Analyzer().analyze(model);
        StateMachine signal = machine(r, "Signal");
        assertEquals(Set.of("Red", "Amber"), ids(signal.directBranches()));
        assertEquals(List.of("Blink", "Solid"), signal.atomicStates().stream().map(State::id).sorted().toList(),
                "Blink is counted once, not once per branch");
        assertTrue(signal.duplicateStateIds().isEmpty(),
                "one type in two places is an overlap, not two states colliding on an id");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.where().equals("statelevels.Signal")
                && d.message().contains("statelevels.Blink") && d.message().contains("2 direct branches")));

        // SCXML allows one parent and unique ids: Blink is declared exactly once.
        String scxml = new ScxmlSerializer().serialize(signal);
        assertEquals(1, scxml.split("id=\"Blink\"", -1).length - 1, scxml);
        assertFalse(scxml.contains("<state id=\"Amber\" initial="),
                "a compound state's initial must name a child declared inside it: " + scxml);
        assertTrue(scxml.contains("states: 2 atomic (2 direct branch(es), 2 grouping node(s))"), scxml);
        String dot = new DotSerializer().serialize(signal);
        assertEquals(1, dot.split("\n\\s*\"Blink\"(;| \\[)", -1).length - 1, dot);
    }

    @Test
    void theCorpusSeparatesTheLevels() {
        // valueforms: Signal permits Idle, Armed, Firing, Phase, with Phase an enum
        // of RAMP and PEAK. The old single count said 6.
        StateMachine signal = machine(new Analyzer().analyze(modelOf("examples/valueforms")), "Signal");
        assertEquals(4, signal.directBranches().size());
        assertEquals(5, signal.atomicStates().size());
        assertEquals(Set.of("Phase"), ids(signal.compositeNodes()));
        assertEquals(6, signal.allStates().size(), "the node view is unchanged");

        // emptycandidate: a provisional candidate with a sealed and an enum branch.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/emptycandidate"));
        Candidate channel = r.candidates().stream().filter(c -> c.name().equals("Channel"))
                .findFirst().orElseThrow();
        assertEquals(3, channel.directBranches().size());
        assertEquals(2, channel.compositeNodes().size());
        assertEquals(channel.allStates().size() - 2, channel.atomicStates().size());
    }

    @Test
    void theReportNamesTheLevelItCounts() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/valueforms"));
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.message().contains("4 direct branch(es), "
                        + "5 atomic state(s) (1 grouping node(s))")),
                r.diagnostics().stream().map(ExtractionResult.Diagnostic::message).toList().toString());
        assertFalse(r.diagnostics().stream().anyMatch(d -> d.message().matches(".*; 6 states, .*")),
                "no report may count the grouping node as a state");
    }
}
