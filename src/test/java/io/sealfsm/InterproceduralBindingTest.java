package io.sealfsm;

import io.sealfsm.extract.TransitionExtractor;
import io.sealfsm.model.CommitEvidence;
import io.sealfsm.model.CommitForm;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.SuccessorForm;
import io.sealfsm.model.Transition;
import io.sealfsm.serialize.DotSerializer;
import io.sealfsm.serialize.ScxmlSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F29 — caller bindings are a property of the inter-procedural TRAVERSAL, so every
 * axis cell whose successor or committed value crosses a call boundary gets them
 * from one mechanism. Fixture {@code src/test/resources/bindingframes}: one
 * hierarchy per question, all compiled by {@code javac}, each asserted edge by edge
 * so a score cannot pass for a relation.
 *
 * <p>The numbers before F29 are recorded against each test, because several of
 * the negatives FAILED IN THE UNSAFE DIRECTION: {@code Gear} read a clean 5/5 of
 * which every edge was a self-loop, {@code Pump} read 2/2 with one edge deleted,
 * {@code Beacon} read 3/3 with one edge fabricated and the real one dropped.
 */
class InterproceduralBindingTest {

    private static ExtractionResult result;

    @BeforeAll
    static void analyse() {
        result = new Analyzer().analyze(modelOf("src/test/resources/bindingframes"));
    }

    // ---- positives ----------------------------------------------------------

    /**
     * POSITIVE A — one hop into a VOID callee. {@code install(new Launching(), why)}
     * commits its first parameter by writing the state field; with two parameters
     * it is not a recognised mutator, so the commit is proven by the k = 1 probe,
     * which never asks WHICH value. Before F29 the fold entered only callees that
     * return a state, so the arm was "commit proven, successor unknown" with the
     * successor sitting in the argument list: 0/3, Tier 2.
     *
     * <p>{@code recall} is the control: its write installs a value computed in the
     * callee from nothing the caller handed it, so the probe's gap marker must
     * stand exactly as it did.
     */
    @Test
    void positiveA_aVoidCalleeCommitsTheArgumentTheArmHandedIt() {
        StateMachine m = named("Bay");
        assertEquals(3, m.allStates().size());
        assertEquals(3, m.transitions().size(), "every arm recorded, before and after");
        assertEquals(2, m.resolvedTransitionCount());
        assertTrue(hasResolved(m, "Docked", "Launching"));
        assertTrue(hasResolved(m, "Launching", "Away"));
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Away")
                        && t.note() != null && t.note().contains("commit proven inside the callee")),
                "a commit whose value the caller did not hand in keeps the probe's marker, verbatim");
        assertEquals(CommitEvidence.VIA_CALLEE, m.commitEvidence(),
                "the commit is still the probe's; only the successor was recovered");
        assertTrue(m.commitForms().contains(CommitForm.FIELD_MUTATION));
        assertFalse(m.isDetectedEmpty(), "no longer Tier 2");
    }

    /**
     * POSITIVE B — structurally different from A on every named axis: the
     * CARRIER_RETURN cell (A is FIELD_MUTATION in a void callee), TWO hops (A has
     * one), and a successor produced by a STATIC FACTORY (A writes a
     * construction). Before F29: 0/3 — for two independent reasons, both closed
     * here and pinned separately by ablation in FIXLOG: rule (5) resolved the
     * bound {@code Valves.opening()} with the pure resolver, which never folds;
     * and its cycle guard was a structural HashSet, so {@code relay}'s {@code v}
     * and {@code pack}'s {@code v} compared equal and the chain stopped at hop two.
     */
    @Test
    void positiveB_aFactoryProducedSuccessorSurvivesTwoHopsIntoACarrierSlot() {
        StateMachine m = named("Valve");
        assertTrue(m.commitForms().contains(CommitForm.CARRIER_RETURN));
        assertEquals(3, m.transitions().size());
        assertEquals(3, m.resolvedTransitionCount());
        assertTrue(hasResolved(m, "Shut", "Opening"));
        assertTrue(hasResolved(m, "Opening", "Open"));
        assertTrue(hasResolved(m, "Open", "Shut"));
        assertEquals(Set.of(SuccessorForm.CONSTRUCTION), m.successorForms(),
                "each successor is the factory's construction, reached through the binding");
    }

    /**
     * POSITIVE C — {@code this} inside a folded callee is the RECEIVER the caller
     * wrote. Before F29 every such {@code this} read as a self-loop to the caller's
     * from-state: a clean 5/5 in which Park could never leave Park.
     */
    @Test
    void positiveC_thisInsideAFoldedCalleeResolvesThroughTheReceiverSlot() {
        StateMachine m = named("Gear");
        assertEquals(5, m.transitions().size());
        assertEquals(4, m.resolvedTransitionCount());
        assertTrue(hasResolved(m, "Park", "Drive"), "a constructed receiver is the successor");
        assertTrue(hasResolved(m, "Drive", "Reverse"),
                "a factory-produced receiver is folded in the frame it is written in");
        assertTrue(hasResolved(m, "Park", "Park"), "an explicit `this` receiver is a real self-loop");
        assertTrue(hasResolved(m, "Drive", "Drive"), "so is an implicit one");
        assertFalse(hasResolved(m, "Reverse", "Reverse"),
                "a receiver nothing can read is a gap, never the current state by default");
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Reverse")));
        assertTrue(m.successorForms().contains(SuccessorForm.CONSTRUCTION));
    }

    // ---- negatives ----------------------------------------------------------

    /** NEGATIVE A — a parameter reassigned before it is returned is not bound. */
    @Test
    void negativeA_aReassignedParameterIsNotBoundAndItsEdgeIsKept() {
        StateMachine m = named("Lamp");
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Dim")),
                "UNRESOLVED, edge present");
        assertTrue(m.transitions().stream().noneMatch(t -> t.isResolved() && t.from().equals("Dim")),
                "neither the argument nor the overwrite may be published as the successor");
    }

    /**
     * NEGATIVE B — a virtual call with two bodies in the model. {@code Primed} is
     * the same method through a {@code PlainRouter}-typed receiver, which has one
     * body, and resolves. Before F29: 2/2, with {@code Idle -> Primed} resolved off
     * one of two possible bodies and {@code Running}'s edge DELETED by F9 reading
     * the always-throwing {@code Router#reject} while {@code LoudRouter#reject}
     * returns a state.
     */
    @Test
    void negativeB_aVirtualCallWithTwoBodiesIsNotFoldedAndNotSuppressed() {
        StateMachine m = named("Pump");
        assertEquals(3, m.transitions().size(), "the F9-deleted edge is back, as a gap");
        assertEquals(1, m.resolvedTransitionCount());
        assertTrue(hasResolved(m, "Primed", "Running"), "one body: the binding resolves");
        assertFalse(hasResolved(m, "Idle", "Primed"), "two bodies: not the bound one's answer");
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Idle")));
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Running")),
                "F9 may only read the body that runs; the edge is recorded, not suppressed");
    }

    /**
     * F9 on the CARRIER path may only read the body that runs. {@code
     * gate.refuse(t)} and {@code strict.refuse(c)} are one method whose bound body
     * always throws; through a {@code Gate}-typed receiver another body can run
     * and return a carrier, so that arm is a recorded gap — before F29 F9 deleted
     * it (1/1). Through the final {@code StrictGate} the body is unique and F9's
     * proof holds: no edge, exactly as before.
     */
    @Test
    void f9OnTheCarrierPathMayOnlyReadTheBodyThatRuns() {
        StateMachine m = named("Winch");
        assertTrue(m.commitForms().contains(CommitForm.CARRIER_RETURN));
        assertEquals(2, m.transitions().size());
        assertEquals(1, m.resolvedTransitionCount());
        assertTrue(hasResolved(m, "Slack", "Taut"));
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Taut")),
                "a virtual always-throwing callee is a gap, not an undefined input");
        assertTrue(m.transitions().stream().noneMatch(t -> t.from().equals("Coiled")),
                "a unique always-throwing callee is still an undefined input");
    }

    /**
     * NEGATIVE C — a chain longer than the budget stays UNRESOLVED; the same
     * forwarders one hop shorter resolve. The control also pins the identity-keyed
     * cycle guard: {@code second}'s {@code x} and {@code third}'s {@code x} are
     * structurally equal, and before F29 that alone stopped this chain (0/3).
     */
    @Test
    void negativeC_aChainLongerThanTheBudgetStaysUnresolved() {
        assertEquals(2, TransitionExtractor.maxInterproceduralDepth(),
                "the committed budget; the depth sweep in FIXLOG justifies it");
        StateMachine m = named("Lamp");
        assertEquals(3, m.transitions().size());
        assertEquals(1, m.resolvedTransitionCount());
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Off")),
                "three hops against a budget of two: UNRESOLVED, edge present");
        assertTrue(hasResolved(m, "On", "Dim"), "two hops, same forwarders, same parameter name");
    }

    /**
     * NEGATIVE D — a parameter read that may run after the call returns is not
     * bound: inside a stored lambda (reached only through a library interface) and
     * inside a local class whose method IS folded — the case the boundary rule
     * has to catch explicitly. The same read in the callee's own body resolves.
     */
    @Test
    void negativeD_aReadAcrossADeferredExecutionBoundaryIsNotBound() {
        StateMachine m = named("Latch");
        assertEquals(3, m.transitions().size());
        assertEquals(1, m.resolvedTransitionCount());
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Open")));
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Shut")));
        assertTrue(hasResolved(m, "Stuck", "Open"));
    }

    // ---- the traversal's own invariants --------------------------------------

    /**
     * Recursion is decided on the DECLARATION's identity. {@code TideDriver.step}
     * and {@code TideHelper.step} have identical signature strings; keyed on the
     * string, the second hop read as the first recursing and was declined (0/2).
     */
    @Test
    void recursionIsDecidedOnTheDeclarationNotOnTheSignatureString() {
        StateMachine m = named("Tide");
        assertEquals(2, m.transitions().size());
        assertEquals(2, m.resolvedTransitionCount());
        assertTrue(hasResolved(m, "Ebb", "Flood"));
        assertTrue(hasResolved(m, "Flood", "Ebb"));
    }

    /**
     * A state switch inside a folded callee discriminates what its selector is
     * BOUND to. Before F29 it was assumed to be the current state: {@code
     * settle(new Blink())} from the {@code Dark} arm skipped the {@code Blink} arm
     * that runs and published {@code Dark -> Dark} — 3/3, one fabricated, one
     * dropped. With the selector bound to the current state the reading is F18's,
     * unchanged; bound to something unreadable, every arm may run, each under its
     * own test and all from the caller's state.
     */
    @Test
    void aSwitchInsideAFoldDiscriminatesWhatItsSelectorIsBoundTo() {
        StateMachine m = named("Beacon");
        assertTrue(hasResolved(m, "Dark", "Steady"), "the binding says exactly which arm runs");
        assertFalse(hasResolved(m, "Dark", "Dark"), "the fabricated edge must not come back");
        assertTrue(hasResolved(m, "Blink", "Steady"), "bound to the current state: F18 unchanged");
        Map<String, String> steady = new LinkedHashMap<>();
        for (Transition t : m.transitions()) {
            if (t.isResolved() && t.from().equals("Steady")) steady.put(t.to(), t.guard());
        }
        assertEquals(Set.of("Dark", "Steady", "Blink"), steady.keySet(),
                "an unreadable binding excludes nothing");
        assertTrue(steady.values().stream().allMatch(g -> g != null && g.contains("instanceof")),
                "each arm carries its own test, so none reads as firing unconditionally: " + steady);
    }

    /**
     * An overload Spoon chose by guess is not the call. Under {@code noClasspath}
     * an argument of unresolved type leaves JDT nothing to choose with, and Spoon
     * binds the first same-arity declaration (measured). Before F29 {@code
     * callambiguity} published {@code Low -> High} RESOLVED off the wrong overload;
     * the real one returns {@code Low}. The control has no overload and resolves.
     */
    @Test
    void anOverloadChosenByGuessIsNotFolded() {
        ExtractionResult r = new Analyzer().analyze(modelOf("src/test/resources/callambiguity"));
        StateMachine m = r.machines().stream().filter(x -> x.name().equals("Knob")).findFirst().orElseThrow();
        assertFalse(hasResolved(m, "Low", "High"), "the guessed overload's answer is not published");
        assertTrue(m.transitions().stream().anyMatch(t -> !t.isResolved() && t.from().equals("Low")),
                "UNRESOLVED, edge present");
        assertTrue(hasResolved(m, "High", "Low"), "no overload, no guess: the binding is exact");
    }

    // ---- --explain ------------------------------------------------------------

    /**
     * {@code --explain} names the chain a resolved value travelled and the rule
     * that stopped an unresolved one — which is what separates a capability gap
     * from a scope limit, since both are the same dashed edge in the output.
     */
    @Test
    void explainNamesTheBindingChainAndTheRuleThatStoppedIt() {
        ExtractionResult r = new Analyzer().explaining(true)
                .analyze(modelOf("src/test/resources/bindingframes"));
        Map<String, String> traces = r.bindingTraces().stream().collect(Collectors.toMap(
                t -> t.where().substring(t.where().lastIndexOf('.') + 1),
                t -> String.join("\n", t.lines())));

        assertTrue(traces.get("Lamp").contains("DEPTH_EXCEEDED"), traces.get("Lamp"));
        assertTrue(traces.get("Lamp").contains("REASSIGNED_PARAMETER"), traces.get("Lamp"));
        assertTrue(traces.get("Lamp").contains("resolved On --> Dim through 2 binding hop(s)"),
                traces.get("Lamp"));
        assertTrue(traces.get("Pump").contains("OVERRIDDEN"), traces.get("Pump"));
        assertTrue(traces.get("Latch").contains("DEFERRED_EXECUTION"), traces.get("Latch"));
        assertTrue(traces.get("Latch").contains("LIBRARY"), traces.get("Latch"));
        assertTrue(traces.get("Valve").contains("(evaluated in the caller's frame)"), traces.get("Valve"));
        assertTrue(traces.get("Gear").contains("`this` := receiver"), traces.get("Gear"));
        assertTrue(traces.get("Bay").contains("NOTHING_BOUND"), traces.get("Bay"));
    }

    /** The trace is a side channel: asking for it changes no output byte. */
    @Test
    void explainingChangesNoOutput() {
        ExtractionResult quiet = new Analyzer().analyze(modelOf("src/test/resources/bindingframes"));
        ExtractionResult loud = new Analyzer().explaining(true)
                .analyze(modelOf("src/test/resources/bindingframes"));
        assertEquals(render(quiet), render(loud));
    }

    // ---- determinism ------------------------------------------------------------

    /**
     * Identical input, run twice from two independently built models, gives
     * byte-identical DOT and SCXML. Asked of this fixture and of the two corpus
     * machines whose relations live almost entirely in folded helpers, where an
     * identity-keyed map iterated in hash order would be the first thing to show.
     */
    @Test
    void identicalInputRunTwiceGivesByteIdenticalOutput() {
        for (String dir : List.of("src/test/resources/bindingframes", "src/test/resources/foldbinding",
                "examples/lcp_automation", "examples/lcp_automation_chatgpt")) {
            String first = render(new Analyzer().analyze(modelOf(dir)));
            String second = render(new Analyzer().analyze(modelOf(dir)));
            assertEquals(first, second, dir);
        }
    }

    // ---- helpers --------------------------------------------------------------

    private static String render(ExtractionResult r) {
        DotSerializer dot = new DotSerializer();
        ScxmlSerializer scxml = new ScxmlSerializer();
        StringBuilder sb = new StringBuilder();
        r.machines().stream()
                .sorted((a, b) -> a.qualifiedName().compareTo(b.qualifiedName()))
                .forEach(m -> sb.append(dot.serialize(m)).append('\n').append(scxml.serialize(m)).append('\n'));
        return sb.toString();
    }

    private static StateMachine named(String name) {
        return result.machines().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no machine named " + name));
    }

    private static boolean hasResolved(StateMachine m, String from, String to) {
        return m.transitions().stream()
                .anyMatch(t -> t.isResolved() && t.from().equals(from) && to.equals(t.to()));
    }

    private static CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }
}
