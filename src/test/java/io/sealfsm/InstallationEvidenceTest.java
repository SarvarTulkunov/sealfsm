package io.sealfsm;

import io.sealfsm.detect.DispatchCommitDetector;
import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.detect.dispatch.DispatchFinder;
import io.sealfsm.detect.dispatch.Installation;
import io.sealfsm.model.Candidate;
import io.sealfsm.model.CommitEvidence;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F36, thesis Decision 4: <em>converters are not FSMs</em>, and a returned
 * hierarchy value is a transition only where a caller installs it as the current
 * state.
 *
 * <p>The decision names its acceptance examples, and each is asserted here. It
 * also asks for three kinds of control: converter use, a demonstrated state
 * update, and missing callers. Each exists at two loci (a typed handler in
 * {@code examples/typedhandler}, and a switch in {@code examples/converters}), and
 * the per-state spelling as well ({@code converters.Currency}), because a rule
 * about what a commit IS must hold at every locus or it splits one converter's
 * verdict by spelling.
 *
 * <p>Three outcomes are kept apart, as the decision keeps them apart:
 * <ul>
 *   <li>a caller installs the result, so it is a <b>machine</b>;</li>
 *   <li>callers exist and every one uses the result as data, so it is an
 *       <b>established conversion</b>: rejected, and published on neither
 *       channel;</li>
 *   <li>no caller exists, or one hands the value out of sight, so the tool
 *       <b>abstains</b>. Missing caller evidence is uncertainty, not proof of a
 *       conversion. Where the sites amount to a dispatch the hierarchy is a
 *       PROVISIONAL candidate.</li>
 * </ul>
 */
class InstallationEvidenceTest {

    private static CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    private static ExtractionResult analyze(String path) {
        return new Analyzer().analyze(modelOf(path));
    }

    private static StateMachine machine(ExtractionResult r, String name) {
        return r.machines().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no machine " + name));
    }

    private static boolean isMachine(ExtractionResult r, String name) {
        return r.machines().stream().anyMatch(m -> m.name().equals(name));
    }

    private static Candidate candidate(ExtractionResult r, String name) {
        return r.candidates().stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no candidate " + name));
    }

    private static boolean isCandidate(ExtractionResult r, String name) {
        return r.candidates().stream().anyMatch(c -> c.name().equals(name));
    }

    private static ExtractionResult.Outcome outcome(ExtractionResult r, String qualifiedName) {
        return r.outcomes().stream().filter(o -> o.qualifiedName().equals(qualifiedName))
                .map(ExtractionResult.RootOutcome::outcome).findFirst()
                .orElseThrow(() -> new AssertionError("no outcome for " + qualifiedName));
    }

    private static Set<String> resolvedPairs(StateMachine m) {
        return m.transitions().stream().filter(Transition::isResolved)
                .map(t -> t.from() + "->" + t.to()).collect(Collectors.toSet());
    }

    // ---- the decision's acceptance examples ---------------------------------

    @Test
    void lengthCeasesToAppearAsAMachine() {
        // "The existing Length fixture must cease to appear as a machine with
        // Meters -> Feet and Feet -> Meters." It has no caller at all, and missing
        // caller evidence is uncertainty. So it is not rejected as a converter: it
        // is a PROVISIONAL candidate, because two handlers fixing two source states
        // amount to a plausible dispatch.
        ExtractionResult r = analyze("examples/typedhandler");
        assertFalse(isMachine(r, "Length"));
        Candidate length = candidate(r, "Length");
        assertTrue(length.isProvisional());
        assertEquals(Set.of(Candidate.Basis.INSTALLATION_UNSHOWN), length.basis());
        assertTrue(length.reason().contains("no caller in the source set"), length.reason());
        assertEquals(ExtractionResult.Outcome.CANDIDATE, outcome(r, "typedhandler.Length"));
    }

    @Test
    void aGenuineOneHandlerMachineIsRecoveredWhenItsSuccessorBecomesTheCurrentState() {
        // "A genuine one-handler machine such as Lamp should be recoverable when its
        // surrounding code shows the successor becoming the current state." F35
        // rejected it as the documented cost of a family threshold.
        StateMachine lamp = machine(analyze("examples/typedhandler"), "Lamp");
        assertEquals(Set.of("Dark->Lit"), resolvedPairs(lamp));
        assertEquals(1, lamp.transitions().size());
        assertEquals(CommitEvidence.VIA_CALLER, lamp.commitEvidence(),
                "the commit is seen at the caller that stores the result back");
    }

    @Test
    void aLoneUncalledConverterIsAPlainAbstentionAndNotACandidate() {
        // One handler, one source state, no caller. Not a machine: nothing shows an
        // installation. Not a candidate: one discriminated state is not a dispatch
        // (F35's threshold, retired as an acceptance rule and kept as plausibility).
        ExtractionResult r = analyze("examples/typedhandler");
        assertFalse(isMachine(r, "Shape"));
        assertFalse(isCandidate(r, "Shape"));
        assertEquals(ExtractionResult.Outcome.ABSTAINED, outcome(r, "typedhandler.Shape"));
    }

    // ---- the three controls, typed-handler spelling --------------------------

    @Test
    void aConverterWhoseResultIsUsedAsDataIsRejectedOnBothChannels() {
        // "Reject a hierarchy established as converter-only without publishing its
        // members on either the machine or candidate channel as FSM states."
        // Temperature is Length's shape with callers, and every caller switches over
        // the result to build a String. Its Display.label is itself a fold over the
        // hierarchy, which before F36 was enough to make it a candidate.
        ExtractionResult r = analyze("examples/typedhandler");
        assertFalse(isMachine(r, "Temperature"));
        assertFalse(isCandidate(r, "Temperature"),
                "a known conversion must not become a reported state set");
        assertEquals(ExtractionResult.Outcome.CONVERTED, outcome(r, "typedhandler.Temperature"));
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.where().equals("typedhandler.Temperature")
                && d.message().contains("REJECTED AS A CONVERSION")));
    }

    @Test
    void aPipelineWithoutItsDriverIsMissingCallerEvidence() {
        // The same order pipeline as examples/orchestrator with no driver: its
        // handlers look exactly like Length's converters, and it gets exactly
        // Length's verdict.
        ExtractionResult r = analyze("examples/typedhandler");
        assertFalse(isMachine(r, "OrderState"));
        assertEquals(Set.of(Candidate.Basis.INSTALLATION_UNSHOWN), candidate(r, "OrderState").basis());
        assertTrue(isMachine(analyze("examples/orchestrator"), "OrderState"),
                "with its run-to-completion driver the re-entry is the store-back");
    }

    // ---- the same controls at every locus ----------------------------------

    @Test
    void theStoreBackRuleHoldsAtEveryLocus() {
        // Four hierarchies held to one converter shape and varied only in what
        // happens to the result. A centralized switch: stored back (Tint), used as
        // data (Shade), never called (Hue). A per-state override: used as data
        // (Currency).
        ExtractionResult r = analyze("examples/converters");
        assertEquals(Set.of("Tint"), r.machines().stream().map(StateMachine::name).collect(Collectors.toSet()));
        assertEquals(Set.of("Hue"), r.candidates().stream().map(Candidate::name).collect(Collectors.toSet()));
        assertEquals(ExtractionResult.Outcome.CONVERTED, outcome(r, "converters.Shade"));
        assertEquals(ExtractionResult.Outcome.CONVERTED, outcome(r, "converters.Currency"));
        assertEquals(ExtractionResult.Outcome.CANDIDATE, outcome(r, "converters.Hue"));
        assertEquals(ExtractionResult.Outcome.MACHINE, outcome(r, "converters.Tint"));

        StateMachine tint = machine(r, "Tint");
        assertEquals(Set.of("Warm->Cool", "Cool->Warm"), resolvedPairs(tint));
        assertEquals(CommitEvidence.VIA_CALLER, tint.commitEvidence());
    }

    // ---- what counts as installation, at the unit level ----------------------

    @Test
    void eachVerdictNamesTheEvidenceItRestsOn() {
        CtModel model = modelOf("examples/typedhandler");
        assertVerdicts(model, "typedhandler.Lamp", Installation.Verdict.INSTALLED, "LampPanel.press");
        assertVerdicts(model, "typedhandler.Temperature", Installation.Verdict.CONVERTED, "switched over");
        assertVerdicts(model, "typedhandler.Length", Installation.Verdict.NO_CALLER, "no call to");
    }

    @Test
    void aStoreBackIsRecognisedHoweverItIsSpelled() {
        // A root-typed field (traffic: current = current.next()), a local the call
        // read its state from (lcp_automation: currentState = sm.transition(
        // currentState, event)), a carrier unwrapped first (tcp: this.state =
        // t.next()), a run-to-completion re-entry (orchestrator).
        assertVerdicts(modelOf("examples/traffic"), "examples.traffic.TrafficLight",
                Installation.Verdict.INSTALLED, "root-typed field 'current'");
        assertVerdicts(modelOf("examples/lcp_automation"), "lcp.LcpState",
                Installation.Verdict.INSTALLED, "replaces the state variable 'currentState'");
        assertVerdicts(modelOf("examples/tcp"), "tcp.TcpState",
                Installation.Verdict.INSTALLED, "TcpConnection.apply");

        CtModel orch = modelOf("examples/orchestrator");
        Installation.Report report = report(orch, "orchestrator.OrderState");
        assertTrue(report.all().stream().anyMatch(v -> v.verdict() == Installation.Verdict.REENTRY
                && v.hostName().equals("OrderOrchestrator.orchestrate")));
        assertTrue(report.all().stream().filter(v -> v.hostName().equals("OrderOrchestratorImpl.handle"))
                .allMatch(v -> v.verdict() == Installation.Verdict.INSTALLED
                        && v.detail().contains("run-to-completion driver")));
    }

    @Test
    void aLibraryContainerIsNotEvidenceOfInstallation() {
        // L2: AtomicReference.accumulateAndGet does install the callable's result,
        // but the tool never reads a JDK body (F11), so nothing it READ shows an
        // installation. Uncertain, not converted: a provisional candidate.
        ExtractionResult r = analyze("examples/cancellation");
        assertTrue(r.machines().isEmpty());
        Candidate c = candidate(r, "CancellationState");
        assertTrue(c.reason().contains("accumulateAndGet"), c.reason());
        // The twin that installs in the model is a machine.
        assertEquals(1, analyze("examples/functionaldriver").machines().size());
    }

    @Test
    void aMarkerStillDecidesAndCarriesNoInstallationReport() {
        // @Fsm is an explicit statement that the hierarchy is a machine; it supplies
        // the classification the evidence would otherwise have to prove.
        CtModel model = modelOf("examples/nondeterministic");
        CtType<?> vend = type(model, "examples.nondeterministic.Vend");
        StateMachineClassifier.Classification c = new StateMachineClassifier().classify(vend, model);
        assertTrue(c.isStateMachine());
        assertNull(c.installation());
    }

    // ---- corpus-wide: the decision's invariants, over every fixture ----------

    @Test
    void acrossTheCorpusAConversionIsPublishedOnNeitherChannelAndEveryCandidateIsProvisional()
            throws IOException {
        List<Path> fixtures;
        try (Stream<Path> dirs = Files.list(Path.of("examples"))) {
            fixtures = dirs.filter(Files::isDirectory).sorted().toList();
        }
        int converted = 0;
        for (Path dir : fixtures) {
            ExtractionResult r = analyze(dir.toString());
            Set<String> published = Stream.concat(
                    r.machines().stream().map(StateMachine::qualifiedName),
                    r.candidates().stream().map(Candidate::qualifiedName)).collect(Collectors.toSet());
            for (ExtractionResult.RootOutcome o : r.outcomes()) {
                if (o.outcome() != ExtractionResult.Outcome.CONVERTED) continue;
                converted++;
                assertFalse(published.contains(o.qualifiedName()),
                        dir + ": " + o.qualifiedName() + " is a conversion and must not be published");
            }
            for (Candidate c : r.candidates()) {
                assertTrue(c.isProvisional(), dir + ": " + c.qualifiedName());
                assertFalse(c.basis().isEmpty(), dir + ": " + c.qualifiedName() + " must name its gap");
            }
            for (StateMachine m : r.machines()) {
                assertTrue(m.commitEvidence() != CommitEvidence.UNPROVEN,
                        dir + ": a machine rests on an established commit");
            }
        }
        assertTrue(converted >= 3, "the converter controls are in the corpus: " + converted);
    }

    // ---- helpers --------------------------------------------------------------

    private static CtType<?> type(CtModel model, String qualifiedName) {
        return model.getAllTypes().stream().flatMap(InstallationEvidenceTest::withNested)
                .filter(t -> t.getQualifiedName().equals(qualifiedName)).findFirst()
                .orElseThrow(() -> new AssertionError("no type " + qualifiedName));
    }

    private static Stream<CtType<?>> withNested(CtType<?> t) {
        return Stream.concat(Stream.of(t), t.getNestedTypes().stream().flatMap(InstallationEvidenceTest::withNested));
    }

    private static Installation.Report report(CtModel model, String root) {
        CtType<?> t = type(model, root);
        return Installation.analyze(t, model, DispatchFinder.find(t, model), DispatchCommitDetector.find(t, model));
    }

    /** Every judged site of {@code root} has {@code verdict}, and at least one names {@code evidence}. */
    private static void assertVerdicts(CtModel model, String root, Installation.Verdict verdict,
                                       String evidence) {
        Installation.Report report = report(model, root);
        assertFalse(report.all().isEmpty(), root + " has sites");
        for (Installation.SiteVerdict v : report.all()) {
            assertEquals(verdict, v.verdict(), root + " / " + v.hostName() + ": " + v.detail());
        }
        assertTrue(report.all().stream().anyMatch(v -> v.detail().contains(evidence)),
                root + " names '" + evidence + "': "
                        + report.all().stream().map(Installation.SiteVerdict::detail).toList());
    }
}
