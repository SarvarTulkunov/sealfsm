package io.sealfsm;

import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F38: an event test partitions a CLOSED event alphabet Σ, so the branch taken
 * when it fails fires on the remaining inputs, one transition per input. It is
 * never one eventless edge guarded by the negated test, and never a {@code !Lock}
 * label (a label is a Σ symbol; a negation is not one).
 *
 * <p>Each assertion is on the exact (from, event, to, guard) set, because the
 * failure this closes did not change the edge COUNT's shape in an obvious way: it
 * published {@code Closed --[!(event instanceof Lock)]--> Open}, an eventless
 * edge, where the source says {@code Push} and {@code Unlock}.
 *
 * <p>The negative controls are {@code eventsplit.Pump} (an OPEN event type: no Σ,
 * nothing to take the complement in) and {@code eventsplit.Gate} (two event
 * parameters: "not TURN" says nothing about the other one). Both must keep the
 * guard reading unchanged.
 */
class EventPartitionTest {

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

    /** "from --event [guard]--> to"; an eventless edge reads "--eventless-->". */
    private static Set<String> edges(StateMachine m) {
        return m.transitions().stream()
                .map(t -> t.from() + " --" + (t.event() == null ? "eventless" : t.event())
                        + (t.guard() == null ? "" : " [" + t.guard() + "]") + "--> "
                        + (t.isResolved() ? t.to() : "?"))
                .collect(Collectors.toSet());
    }

    @Test
    void doorElseBranchIsOneEdgePerRemainingEvent() {
        StateMachine door = machine(new Analyzer().analyze(modelOf("examples/door")), "Door");
        assertEquals(Set.of(
                "Closed --Lock--> Locked",
                "Closed --Push--> Open",
                "Closed --Unlock--> Open",
                "Open --eventless--> Closed",           // the arm never tests the input: eventless
                "Locked --Unlock--> Closed",
                "Locked --Push--> Locked",
                "Locked --Lock--> Locked"), edges(door));
    }

    @Test
    void ternaryAndFallThroughExpandOverASealedAlphabet() {
        StateMachine latch = machine(new Analyzer().analyze(modelOf("examples/eventsplit")), "Latch");
        assertEquals(Set.of(
                "Idle --Arm--> Armed",
                "Idle --Fire--> Idle",
                "Idle --Reset--> Idle",
                "Armed --Fire--> Fired",
                "Armed --Reset--> Idle",
                "Armed --Arm--> Armed",       // the fall-through, only on what is left
                "Fired --eventless--> Idle"),          // never tests the input: stays eventless
                edges(latch));
    }

    @Test
    void conjoinedDataConditionAndInequalityOverAnEnumAlphabet() {
        StateMachine valve = machine(new Analyzer().analyze(modelOf("examples/eventsplit")), "Valve");
        Set<String> e = edges(valve);
        assertEquals(7, e.size(), e.toString());
        assertTrue(e.contains("Shut --OPEN [(pressure < 10)]--> Open"), e.toString());
        // the else-branch: OPEN when the data condition fails, every other input always
        assertTrue(e.contains("Shut --OPEN [!((pressure < 10))]--> Shut"), e.toString());
        assertTrue(e.contains("Shut --SHUT--> Shut"), e.toString());
        assertTrue(e.contains("Shut --BLEED--> Shut"), e.toString());
        // `knob != Knob.SHUT` is the complement written directly
        assertTrue(e.contains("Open --OPEN--> Open"), e.toString());
        assertTrue(e.contains("Open --BLEED--> Open"), e.toString());
        assertTrue(e.contains("Open --SHUT--> Shut"), e.toString());
    }

    @Test
    void anOpenEventTypeIsNotExpanded() {
        StateMachine pump = machine(new Analyzer().analyze(modelOf("examples/eventsplit")), "Pump");
        assertEquals(4, pump.transitions().size());
        for (Transition t : pump.transitions()) {
            assertEquals(null, t.event(), "no Σ to label with: " + t);
            assertTrue(t.guard() != null && t.guard().contains("signal instanceof"),
                    "the test stays a guard: " + t);
        }
    }

    @Test
    void twoEventParametersAreNotExpanded() {
        StateMachine gate = machine(new Analyzer().analyze(modelOf("examples/eventsplit")), "Gate");
        assertEquals(4, gate.transitions().size());
        for (Transition t : gate.transitions()) {
            assertEquals(null, t.event(), "one key label would ignore the mode: " + t);
            assertTrue(t.guard() != null && t.guard().contains("key =="), "the test stays a guard: " + t);
        }
    }

    @Test
    void aBranchNoInputReachesIsReportedNotDrawn() {
        // eventsplit.Bell: Σ = {Hit}, so `strike instanceof Hit ? ... : q` takes its
        // else-branch only on a null strike, which is not an input.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/eventsplit"));
        StateMachine bell = machine(r, "Bell");
        assertEquals(Set.of("Quiet --Hit--> Ringing", "Ringing --eventless--> Quiet"), edges(bell));
        assertTrue(r.diagnostics().stream().anyMatch(d -> "eventsplit.Bell".equals(d.where())
                        && d.message().contains("1 branch(es) are reached by no input")),
                "the dead branch is counted, not silently absent");
    }

    @Test
    void aBranchDeadForOneInputButLiveForAnotherIsNotCountedDead() {
        // eventsplit.Latch walks its Armed fall-through once per input; the
        // `if (cmd instanceof Reset)` branch is dead for Arm and live for Reset.
        // Counting it dead would also mark its returns accounted (F18) while
        // another path still needs them, which is how a real gap would be hidden.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/eventsplit"));
        assertFalse(r.diagnostics().stream().anyMatch(d -> "eventsplit.Latch".equals(d.where())
                        && d.message().contains("reached by no input")),
                "a branch some input reaches is live");
    }

    @Test
    void carrierOtherwiseEdgesBecomeOnePerInput() {
        // examples/tcp: `if (event == UserCall.CLOSE) return ...; return ignore(this);`
        // is RFC 9293's "ignore" cell for every OTHER input, spelled out.
        StateMachine tcp = machine(new Analyzer().analyze(modelOf("examples/tcp")), "TcpState");
        Set<String> e = edges(tcp);
        assertTrue(e.contains("Established --UserCall.CLOSE--> FinWait1"), e.toString());
        assertTrue(e.contains("Established --Timeout.USER--> Established"), e.toString());
        assertTrue(e.contains("CloseWait --SegmentArrival [!(seg.rst())]--> CloseWait"), e.toString());
        for (Transition t : tcp.transitions()) {
            String g = t.guard() == null ? "" : t.guard();
            assertFalse(g.contains("tcp.UserCall.") || g.contains("tcp.Timeout."),
                    "a test of the input is a label, never a guard: " + t);
        }
    }
}
