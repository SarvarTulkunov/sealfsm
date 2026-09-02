package io.sealfsm;

import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.model.Candidate;
import io.sealfsm.model.CommitEvidence;
import io.sealfsm.model.CommitForm;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.SuccessorForm;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>State enumeration is complete regardless of how badly transition extraction
 * does.</b>
 *
 * <p>The tool makes two claims of different strength, and their independence is
 * its central contribution: states come from a compiler-checked {@code permits}
 * clause and are exact; transitions are recovered by intra-procedural data flow
 * and are approximate. Those claims used to be coupled by one {@code continue} in
 * {@code Analyzer} — when no transition producer was recognised the machine was
 * never constructed, {@code StateExtractor} was never called, and the tool
 * reported <b>zero states</b> for a hierarchy whose states were never in doubt.
 *
 * <p>A suite that only checks fixtures whose transitions succeed cannot tell
 * "states are exact" from "states are exact when transitions resolve". So this
 * file's centre is a property test over machines whose transition recovery fails
 * <em>completely and by design</em>, plus fixtures the tool refuses to call
 * machines at all — and it asserts the state set is exact in every one of them.
 */
class StateCompletenessTest {

    private CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    // ---- 1. the property, stated directly -----------------------------------

    /**
     * For <b>every</b> fixture directory, every machine AND every candidate must
     * report exactly the transitive {@code permits} closure of its root, read
     * independently through {@link SpoonCompat} rather than through
     * {@code StateExtractor}.
     *
     * <p>The fixture list is derived from the filesystem, deliberately. A
     * hard-coded list goes stale the moment a fixture is added, and the test then
     * passes while testing a different input than it claims to — which is exactly
     * the failure mode this file exists to rule out one level up.
     *
     * <p>The closure is recomputed here rather than compared against
     * {@code StateExtractor}'s own output, because comparing a function to itself
     * proves nothing. What is asserted is that the reported set equals the set the
     * {@code permits} clauses name: composites contribute their members
     * recursively, and a permitted {@code enum} contributes its constants (they
     * are closed in exactly the way a {@code permits} clause is).
     */
    @Test
    void everyMachineAndCandidateReportsItsCompletePermitsClosure() {
        int machines = 0;
        int candidates = 0;
        for (Path dir : exampleDirectories()) {
            CtModel model = modelOf(dir.toString());
            ExtractionResult r = new Analyzer().analyze(model);

            for (StateMachine m : r.machines()) {
                assertEquals(permitsClosure(model, m.qualifiedName()),
                        qualifiedNames(m.allStates()),
                        dir + ": machine " + m.qualifiedName()
                                + " must report exactly its permits closure");
                machines++;
            }
            for (Candidate c : r.candidates()) {
                assertEquals(permitsClosure(model, c.qualifiedName()),
                        qualifiedNames(c.allStates()),
                        dir + ": candidate " + c.qualifiedName() + " must report exactly its "
                                + "permits closure — the same set the machine path would have "
                                + "produced, not a flattened approximation of it");
                candidates++;
            }
        }
        assertTrue(machines > 30, "the corpus should still be producing machines: " + machines);
        assertTrue(candidates > 0, "the candidate channel must be exercised by the corpus");
    }

    // ---- 2. Tier 2: complete states, empty relation, every arm recorded -------

    /**
     * Fixture 1 — {@code examples/opaquesuccessor}. Centralized locus, commit
     * proven {@code DIRECT}ly by the host's codomain, successors unrecoverable
     * because the helper returns from inside a {@code synchronized} block.
     *
     * <p>If the walker is ever taught to descend {@code synchronized}, this test
     * fails loudly — which is the correct outcome, and the fixture's Javadoc says
     * so. The assertion is about what the walk actually reached, not about a list
     * of constructs someone remembered to extend.
     */
    @Test
    void opaqueSuccessorIsATierTwoMachineWithExactStates() {
        StateMachine m = single(new Analyzer().analyze(modelOf("examples/opaquesuccessor")));
        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of("Idle", "Armed", "Fired", "Spent"), ids(m.allStates()),
                "the permits clause is exact whatever the transitions do");
        assertEquals(CommitEvidence.DIRECT, m.commitEvidence(),
                "the codomain proves this commit; the k = 1 probe is not involved");
        assertEquals(0, m.resolvedTransitionCount());
        assertTrue(m.isDetectedEmpty());
        assertEquals(4, m.transitions().size(), "one unresolved edge per dispatched arm");
        assertEveryEdgeHasARealSource(m);
    }

    /**
     * Fixture 2 — {@code examples/voidcommit}. The cell that needed the k = 1
     * commit-existence probe: the arms are bare calls whose value Java discards,
     * so the commit is only visible inside the {@code void} callee.
     *
     * <p>At {@code master} this whole hierarchy was lost — not merely its
     * transitions but its four states.
     */
    @Test
    void voidCommitIsATierTwoMachineEstablishedByTheProbe() {
        StateMachine m = single(new Analyzer().analyze(modelOf("examples/voidcommit")));
        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of("Empty", "Filling", "Full", "Jammed"), ids(m.allStates()));
        assertEquals(CommitEvidence.VIA_CALLEE, m.commitEvidence(),
                "the commit is an H-typed field write one call away, invisible at the dispatch");
        assertTrue(m.commitForms().contains(CommitForm.FIELD_MUTATION),
                "the FORM is what the source does; the EVIDENCE is how much we had to open");
        assertEquals(0, m.resolvedTransitionCount(),
                "the probe answers whether a successor is installed, never which");
        assertTrue(m.isDetectedEmpty());
        assertEquals(4, m.transitions().size(), "one unresolved edge per dispatched arm");
        assertEveryEdgeHasARealSource(m);
    }

    /**
     * Fixture 5 — {@code examples/opaquepolymorphic}. The same property at the
     * other dispatch locus, so it is <em>demonstrated</em> for per-state dispatch
     * rather than asserted by analogy with the centralized case.
     *
     * <p>Here the source state is exact by construction — it is the declaring
     * class, with no data flow involved — which makes this the cleanest statement
     * that the two claims are independent: every edge's source is known and no
     * edge's target is.
     */
    @Test
    void opaquePolymorphicIsATierTwoMachineAtTheOtherLocus() {
        StateMachine m = single(new Analyzer().analyze(modelOf("examples/opaquepolymorphic")));
        assertEquals(StateMachine.Encoding.POLYMORPHIC, m.encoding());
        assertEquals(Set.of("Parked", "Spinning", "Braking"), ids(m.allStates()));
        assertEquals(CommitEvidence.DIRECT, m.commitEvidence(),
                "a per-state method returning H proves its commit by codomain");
        assertEquals(0, m.resolvedTransitionCount());
        assertTrue(m.isDetectedEmpty());
        assertEquals(3, m.transitions().size(), "one unresolved edge per dispatched state");
        assertEveryEdgeHasARealSource(m);
    }

    // ---- 3. Tier 3: no machine, complete states on the candidate channel ------

    /**
     * Fixture 3 — {@code examples/voidfold}, the load-bearing negative control.
     *
     * <p>Indistinguishable from {@code examples/voidcommit} at the call site; the
     * only difference is that its callee writes a {@code String} field. If this
     * ever yields a machine, the probe has become "any exhaustive switch is a
     * state machine" and the precision claim is gone.
     */
    @Test
    void voidFoldIsACandidateAndNeverAMachine() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/voidfold"));
        assertTrue(r.machines().isEmpty(),
                "a fold into a String is not a transition relation, however deep it hides");
        Candidate c = candidate(r, "voidfold.Hopper");
        assertEquals(Set.of("Empty", "Filling", "Full", "Jammed"), ids(c.allStates()),
                "refusing to call it a machine and refusing to name its states are two "
                        + "different refusals");
        assertFalse(c.dispatchSites().isEmpty(), "the dispatch it was rejected for is named");
        assertTrue(diagnosticsFor(r, "voidfold.Hopper").stream()
                        .anyMatch(d -> d.contains("CANDIDATE") && d.contains("4 state(s)")),
                "a user asking what the states are gets an answer");
    }

    /**
     * Fixture 4 — {@code examples/unreadablecallee}, the F11 control: "I could not
     * read it" must never become "it commits".
     *
     * <p>One level down, F11 is what stops a reflective shadow's empty body from
     * deleting a real edge. Here the same mistake runs the other way and would
     * fabricate a whole machine, which is the worse direction.
     */
    @Test
    void unreadableCalleeIsNotEvidenceOfACommit() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/unreadablecallee"));
        assertTrue(r.machines().isEmpty(), "an unread body proves nothing about a commit");
        Candidate c = candidate(r, "unreadablecallee.Shutter");
        assertEquals(Set.of("Open", "Closing", "Shut"), ids(c.allStates()));
        assertTrue(diagnosticsFor(r, "unreadablecallee.Shutter").stream()
                        .anyMatch(d -> d.contains("could not be read")
                                && d.contains("ShutterSink")),
                "the unreadable callee is NAMED, so 'I could not read it' is separable from "
                        + "'I read it and it commits nothing'");
    }

    /**
     * Fixture 6 — {@code examples/emptycandidate}. A Tier 3 whose permitted
     * subtypes include a nested sealed type and a permitted enum, so the candidate
     * channel is made to report composite and enum-constant expansion rather than
     * the permits clause read literally: 7 states, not 3.
     */
    @Test
    void candidateStatesExpandCompositesAndEnums() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/emptycandidate"));
        assertTrue(r.machines().isEmpty());
        Candidate c = candidate(r, "emptycandidate.Channel");
        assertEquals(7, c.allStates().size(),
                "Active contributes its two members and Draining its two constants");
        assertEquals(Set.of("Idle", "Active", "Reading", "Writing", "Draining",
                        "FLUSHING", "PARKED"), ids(c.allStates()));
        assertEquals(3, c.topLevelStates().size(), "the permits clause itself names three");
        assertTrue(c.topLevelStates().stream().anyMatch(s -> s.id().equals("Active")
                        && s.isComposite() && s.children().size() == 2),
                "nesting is preserved, not flattened away");
    }

    /**
     * {@code examples/shape} is the control for the OTHER half of the Tier 3
     * predicate: a hierarchy nothing discriminates is a plain rejection and must
     * not become a candidate.
     *
     * <p>Dropping the "dispatch present" half would make every sealed type in
     * every model a candidate, at which point the channel distinguishes nothing
     * and says nothing.
     */
    @Test
    void aSumTypeNothingDiscriminatesIsNotACandidate() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/shape"));
        assertTrue(r.machines().isEmpty());
        assertTrue(r.candidates().isEmpty(),
                "no switch, no chain, no dispatch — nothing to be a candidate about");
    }

    /**
     * A VETO releases no candidate. The compositional veto is a positive verdict
     * about the data type — its members are composed into one another, so it is a
     * tree and its "next" is a child — and that verdict binds on its members.
     * Offering it as a candidate would say the tool is undecided about something
     * it decided; it is the same distinction that governs whether a rejected root
     * re-offers its nested hierarchies.
     */
    @Test
    void aVetoedHierarchyIsNotOfferedAsACandidate() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/treebuilder"));
        assertTrue(r.machines().isEmpty());
        assertTrue(r.candidates().isEmpty(), "a recursive data type is a verdict, not a candidate");
    }

    // ---- 3b. F27: the transposed table, and the unmarked empty relation ------

    /**
     * F27 — {@code examples/eventmajor}. A switch over the EVENT whose arms install
     * a state discriminates Σ, not Q, so every recognizer answered "no dispatch" and
     * the hierarchy reported no states either. Both spellings are held on one class
     * — committing directly, and committing one callee deep — so the site count is
     * what proves the probe reaches this locus too.
     *
     * <p>It must be a CANDIDATE and never a machine. A Σ-major arm establishes no
     * source state, so a relation built from one would be sourced entirely at
     * {@code <unknown>}; the tiers exist precisely so a gap in attribution is not
     * dressed as a result.
     */
    @Test
    void eventMajorDispatchReleasesTheStateSet() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/eventmajor"));
        assertTrue(r.machines().isEmpty(),
                "a Σ-major dispatch attributes no source state, so it is evidence for the "
                        + "candidate channel and never for a machine");
        Candidate c = candidate(r, "eventmajor.Link");
        assertEquals(Set.of("Ready", "Active", "Draining", "Closed"), ids(c.allStates()),
                "the permits clause names them exactly, whether or not a relation is recovered");
        assertEquals(2, c.dispatchSites().size(),
                "both spellings are found: the direct field commit and the one a callee deep");
        assertTrue(diagnosticsFor(r, "eventmajor.Link").stream()
                        .anyMatch(d -> d.contains("CANDIDATE") && d.contains("Σ-major")),
                "the reason names the transposition rather than borrowing the state-major "
                        + "sentence, which fails for the opposite reason");
    }

    /**
     * The four negative controls, each holding one requirement. They share the
     * package and the event alphabet with the positive case, so nothing but the
     * clause under test can be what the recognizer reacted to.
     *
     * <ul>
     *   <li>{@code Mode} — the arms fold into a {@code String}. The commit
     *       requirement is not relaxed at this locus; {@code examples/voidfold}'s
     *       guarantee restated. It keeps a {@code Mode} field deliberately, or it
     *       would be excluded by the Q × Σ → Q clause instead and test nothing.</li>
     *   <li>{@code Shade} — commits in two arms, but the host holds no hierarchy
     *       value, so the successor cannot depend on a current state. That is a
     *       factory; JDK 21's {@code VectorShape.forBitSize(int)} and
     *       {@code Opcode.getOpcodeBlock(int)} are the real instances.</li>
     *   <li>{@code Tone} — the same table over an {@code int}. An open selector is
     *       not an alphabet, and admitting one would sweep in every parser.</li>
     *   <li>{@code Beat} — exactly one committing arm, which is a special case
     *       being handled rather than a table.</li>
     * </ul>
     */
    @Test
    void everyEventMajorControlYieldsNothing() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/eventmajor"));
        assertEquals(1, r.candidates().size(),
                "exactly one hierarchy qualifies; the other four are controls and must not");
        assertEquals("eventmajor.Link", r.candidates().get(0).qualifiedName());
        for (String control : List.of("eventmajor.Mode", "eventmajor.Shade",
                "eventmajor.Tone", "eventmajor.Beat")) {
            assertTrue(r.candidates().stream().noneMatch(c -> c.qualifiedName().equals(control)),
                    control + " must not be a candidate — it fails exactly one requirement, and "
                            + "the requirement is what keeps this channel meaningful");
        }
    }

    /**
     * F27, the other half — {@code examples/emptyrelation}. A machine accepted on
     * its helpers' <em>signatures</em> while nothing discriminates the state has no
     * arm to attribute and no reachable production, so the walk yields no
     * transitions at all. That fell through all three tiers and printed as a clean
     * {@code 0/0}, which in a stratified recall table reads as a vacuous row rather
     * than as the total loss it is.
     *
     * <p>An empty relation is the most complete failure of transition recovery
     * there is, so it is the last thing that may go unmarked — and the states are
     * exact regardless, which is the property this whole file exists to pin.
     */
    @Test
    void anEmptyRelationIsMarkedRatherThanPrintedAsACleanScore() {
        StateMachine m = single(new Analyzer().analyze(modelOf("examples/emptyrelation")));
        assertEquals(3, m.allStates().size(), "states are exact however badly transitions do");
        assertTrue(m.transitions().isEmpty(), "the fixture exists to produce no relation at all");
        assertTrue(m.isDetectedEmpty(),
                "0 of 0 is a total loss and must carry a tier, not read as a machine that "
                        + "simply has no transitions");
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/emptyrelation"));
        assertTrue(diagnosticsFor(r, "emptyrelation.Conn").stream()
                        .anyMatch(d -> d.contains("TIER 2")
                                && d.contains("NO arm could be attributed")),
                "the report says which of the two Tier 2 shapes this is: no arm attributable, "
                        + "rather than every arm attributed and no target resolved");
    }

    // ---- 4. every existing golden unchanged ---------------------------------

    /**
     * The frozen oracles. A widening that moves any of these has changed something
     * other than what it claims to, and the point of asserting them in this file —
     * beside the new capability rather than only in the integration suite — is that
     * a probe which "only adds machines" is a claim that has to be checkable in one
     * place.
     */
    @Test
    void everyExistingGoldenIsUnchanged() {
        StateMachine traffic = single(new Analyzer().analyze(modelOf("examples/traffic")));
        assertEquals(3, traffic.allStates().size());
        assertEquals(3, traffic.transitions().size());
        assertEquals(0, traffic.unresolvedTransitionCount(), "traffic: 3 states, 0 unresolved");
        assertEquals(CommitEvidence.DIRECT, traffic.commitEvidence());

        StateMachine tcp = single(new Analyzer().analyze(modelOf("examples/tcp")));
        assertEquals(11, tcp.allStates().size());
        assertTrue(tcp.commitForms().contains(CommitForm.POLY_CARRIER), "tcp: POLY_CARRIER");
        assertEquals(44, tcp.resolvedTransitionCount());
        assertEquals(CommitEvidence.DIRECT, tcp.commitEvidence());

        StateMachine lcp = single(new Analyzer().analyze(modelOf("examples/lcp_automation")));
        assertEquals(10, lcp.allStates().size());
        assertEquals(113, lcp.transitions().size());
        assertEquals(113, lcp.resolvedTransitionCount(), "lcp_automation: 113/113");
        assertEquals(CommitEvidence.DIRECT, lcp.commitEvidence());

        StateMachine gpt = single(new Analyzer().analyze(modelOf("examples/lcp_automation_chatgpt")));
        assertEquals(10, gpt.allStates().size());
        assertEquals(111, gpt.transitions().size());
        assertEquals(111, gpt.resolvedTransitionCount(), "lcp_automation_chatgpt: 111/111");
        assertFalse(gpt.successorForms().contains(SuccessorForm.SELF),
                "SELF absent is the fingerprint that separates F25's 111/111 from the "
                        + "fabricated one it replaced");
        assertEquals(CommitEvidence.DIRECT, gpt.commitEvidence());

        assertTrue(new Analyzer().analyze(modelOf("examples/shape")).machines().isEmpty(),
                "shape stays rejected");
        assertTrue(new Analyzer().analyze(modelOf("examples/foreignfold")).machines().isEmpty(),
                "foreignfold stays rejected — the exhaustive-fold guard is untouched");
        assertTrue(new Analyzer().analyze(modelOf("examples/treebuilder")).machines().isEmpty(),
                "treebuilder stays rejected");

        Set<String> nested = new Analyzer().analyze(modelOf("examples/nestedroots"))
                .machines().stream().map(StateMachine::qualifiedName).collect(Collectors.toSet());
        assertEquals(Set.of("nestedroots.Body"), nested,
                "nestedroots.Node stays vetoed and Message stays an abstention — the probe must "
                        + "not cross the parent/child hierarchy boundary");

        Set<String> chain = new Analyzer().analyze(modelOf("examples/chaindispatch"))
                .machines().stream().map(StateMachine::qualifiedName).collect(Collectors.toSet());
        assertEquals(Set.of("chaindispatch.Relay", "chaindispatch.Shutter"), chain,
                "chaindispatch.Tree and Glyph stay rejected");
    }

    // ---- 5. the probe cannot claim credit for the direct path ----------------

    /**
     * {@code CommitEvidence} is {@code DIRECT} everywhere the direct rules already
     * answered, and {@code VIA_CALLEE} only at the one fixture written for the
     * probe.
     *
     * <p>Without this the probe could quietly start relabelling machines the
     * direct path found, and a later precision or recall gap in the inference
     * would be unattributable — the same argument that split
     * {@code MUTATOR_ARGUMENT} out of {@code FIELD_MUTATION}.
     */
    @Test
    void onlyTheProbeFixtureRestsOnTheProbe() {
        List<String> viaCallee = new ArrayList<>();
        for (Path dir : exampleDirectories()) {
            ExtractionResult r = new Analyzer().analyze(modelOf(dir.toString()));
            for (StateMachine m : r.machines()) {
                if (m.commitEvidence() == CommitEvidence.VIA_CALLEE) {
                    viaCallee.add(m.qualifiedName());
                }
            }
        }
        assertEquals(List.of("voidcommit.Hopper"), viaCallee,
                "exactly one corpus machine rests on the k = 1 probe; every other commit is "
                        + "observed in the dispatch's own syntactic context");
    }

    // ---- helpers -------------------------------------------------------------

    /**
     * The transitive {@code permits} closure of a root, read through
     * {@link SpoonCompat} — independently of {@code StateExtractor}, which is the
     * code under test.
     *
     * <p>A permitted {@code enum} contributes its constants, spelled
     * {@code Owner.CONSTANT}, because an enum's constants are closed in exactly the
     * way a {@code permits} clause is. That spelling is a naming convention rather
     * than the property being tested; what is tested is that the SET matches.
     */
    private Set<String> permitsClosure(CtModel model, String rootQualifiedName) {
        CtType<?> root = model.getAllTypes().stream()
                .flatMap(t -> withNested(t).stream())
                .filter(t -> t.getQualifiedName().equals(rootQualifiedName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("root not in model: " + rootQualifiedName));
        Set<String> out = new LinkedHashSet<>();
        for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(root)) {
            collect(ref, out, new LinkedHashSet<>());
        }
        return out;
    }

    private void collect(CtTypeReference<?> ref, Set<String> out, Set<String> seen) {
        String qn = ref.getQualifiedName();
        if (!seen.add(qn)) return;
        out.add(qn);
        CtType<?> decl = ref.getTypeDeclaration();
        if (decl == null) return;
        if (SpoonCompat.isSealed(decl)) {
            for (CtTypeReference<?> child : SpoonCompat.permittedTypes(decl)) {
                collect(child, out, seen);
            }
        } else if (decl instanceof CtEnum<?> en) {
            for (CtEnumValue<?> constant : en.getEnumValues()) {
                out.add(qn + "." + constant.getSimpleName());
            }
        }
    }

    private static List<CtType<?>> withNested(CtType<?> t) {
        List<CtType<?>> out = new ArrayList<>();
        out.add(t);
        for (CtType<?> n : t.getNestedTypes()) out.addAll(withNested(n));
        return out;
    }

    /** Every fixture directory, from the filesystem — never a hard-coded list. */
    private static List<Path> exampleDirectories() {
        try (var paths = Files.list(Path.of("examples"))) {
            return paths.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> qualifiedNames(List<State> states) {
        return states.stream().map(State::qualifiedName).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<String> ids(List<State> states) {
        return states.stream().map(State::id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static StateMachine single(ExtractionResult r) {
        assertEquals(1, r.machines().size(), "expected exactly one machine, got " + r.machines());
        return r.machines().get(0);
    }

    private static Candidate candidate(ExtractionResult r, String qualifiedName) {
        Candidate c = r.candidates().stream()
                .filter(x -> x.qualifiedName().equals(qualifiedName))
                .findFirst().orElse(null);
        assertNotNull(c, qualifiedName + " must appear on the candidate channel; got "
                + r.candidates().stream().map(Candidate::qualifiedName).toList());
        return c;
    }

    private static List<String> diagnosticsFor(ExtractionResult r, String where) {
        return r.diagnostics().stream()
                .filter(d -> d.where().equals(where))
                .map(ExtractionResult.Diagnostic::message)
                .toList();
    }

    /**
     * Every recorded edge names a declared state as its source.
     *
     * <p>Tier 2's whole content is that a dispatched arm still contributes an edge:
     * the from-state is known — it IS the arm — so an empty transition list, or one
     * sourced at {@code <unknown>}, would be a transition dropped with no
     * unresolved marker, behind a clean-looking {@code 0/0}.
     */
    private static void assertEveryEdgeHasARealSource(StateMachine m) {
        Set<String> declared = ids(m.allStates());
        for (Transition t : m.transitions()) {
            assertTrue(declared.contains(t.from()),
                    "every unresolved edge must leave a declared state, not '" + t.from() + "'");
            assertFalse(t.isResolved(), "this fixture asserts an empty relation: " + t);
        }
    }
}
