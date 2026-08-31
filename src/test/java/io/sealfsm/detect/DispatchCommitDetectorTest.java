package io.sealfsm.detect;

import io.sealfsm.detect.DispatchCommitDetector.Producer;
import io.sealfsm.model.CommitForm;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtIf;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the widened centralized recognizer, at the level where the
 * decision is actually made.
 *
 * <p>The integration tests check that the right machines come out; these check
 * <em>why</em>. The recognizer accepts a switch over the hierarchy on one
 * condition — that its result is committed as a hierarchy value — and everything
 * about its precision follows from that one condition. So the cases below are
 * arranged as near-identical pairs that differ only in the commit:
 *
 * <ul>
 *   <li>{@code state = switch (state) {...}} is accepted;
 *       {@code label = switch (state) {...}} yielding {@code String} is not;</li>
 *   <li>{@code return switch (state) {...}} from a hierarchy-returning method is
 *       accepted; the same expression returned as {@code String} is not.</li>
 * </ul>
 *
 * A recognizer that got this wrong would still pass every positive integration
 * test while quietly reporting exhaustive folds as automata.
 *
 * <p>The {@code instanceof}-chain cases (F17) are arranged the same way, because
 * the chain is admitted on exactly the switch's terms. The pairs there differ
 * only in what the chain produces: an H-typed field write is accepted, a
 * {@code String} field write over the same discrimination is not, and a write
 * that nests one H value inside another is vetoed.
 */
class DispatchCommitDetectorTest {

    private CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    private CtType<?> type(CtModel model, String qualifiedName) {
        // getAllTypes() lists only top-level types; a hierarchy declared inside its
        // driver (examples/cancellation) is reachable only through the elements.
        CtType<?> t = model.getElements(new TypeFilter<>(CtType.class)).stream()
                .filter(x -> x.getQualifiedName().equals(qualifiedName))
                .findFirst().orElse(null);
        assertNotNull(t, "fixture type not found: " + qualifiedName);
        return t;
    }

    private List<Producer> producers(String path, String rootQualifiedName) {
        CtModel model = modelOf(path);
        return DispatchCommitDetector.find(type(model, rootQualifiedName), model);
    }

    private Set<CommitForm> commits(List<Producer> ps) {
        return ps.stream().map(Producer::commit).collect(Collectors.toSet());
    }

    // ---- 3a: the carrier commit, at a CENTRALIZED locus ---------------------

    /**
     * The combination that was unreachable while the two axes were fused.
     *
     * <p>{@code static Step route(Signal, int)} is a centralized transition table
     * whose arms hand the successor to a wrapper. It satisfies both halves of a
     * valid pair — a discrimination over H, and a commit that installs a state —
     * and matched no recognizer: the carrier detector required a per-subtype
     * override, the switch detector required the commit to BE a hierarchy value.
     */
    @Test
    void switchFoldingIntoACarrierWithOneHierarchyComponentIsAProducer() {
        List<Producer> ps = producers("src/test/resources/carrierdispatch",
                "carrierdispatch.Signal");
        assertEquals(1, ps.size(),
                "exactly one of the three switches commits; the other two are controls");
        assertEquals(Set.of(CommitForm.CARRIER_RETURN), commits(ps));
        assertEquals("route", ps.get(0).host().getSimpleName());
    }

    /**
     * The negative control, and the one that matters: the exhaustive-fold guard
     * must survive the widening. {@code describe} is the SAME discrimination over
     * the SAME hierarchy, on the same class, folding into a record with no
     * hierarchy-typed component — so only the codomain separates it from
     * {@code route}, which is exactly the separation the commit requirement
     * exists to make.
     */
    @Test
    void switchFoldingIntoATypeWithNoHierarchyComponentStaysRejected() {
        List<Producer> ps = producers("src/test/resources/carrierdispatch",
                "carrierdispatch.Signal");
        assertTrue(ps.stream().noneMatch(p -> "describe".equals(p.host().getSimpleName())),
                "a fold into a carrier-shaped type with no hierarchy slot is still a fold");
    }

    /**
     * The ambiguity control. {@code fork} folds into a record with TWO
     * hierarchy-typed components, so no slot is <em>the</em> successor. Resolving
     * it by field order would publish a resolved edge to a state chosen by
     * declaration order — a fabrication, and the one failure mode the soundness
     * invariant forbids outright. Declining is the answer.
     */
    @Test
    void carrierWithTwoHierarchyComponentsIsDeclinedRatherThanGuessed() {
        List<Producer> ps = producers("src/test/resources/carrierdispatch",
                "carrierdispatch.Signal");
        assertTrue(ps.stream().noneMatch(p -> "fork".equals(p.host().getSimpleName())),
                "which of two hierarchy-typed slots is the successor is undecidable");
    }

    // ---- accepted commits ---------------------------------------------------

    @Test
    void switchCommittedToAnExplicitThisFieldIsAProducer() {
        // this.currentState = switch (this.currentState) { ... }
        List<Producer> ps = producers("examples/http2-stream-gemini", "http2stream.State");
        assertEquals(1, ps.size(), "exactly one dispatch in the fixture");
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), commits(ps));
        assertEquals("handleEvent", ps.get(0).host().getSimpleName(),
                "the host is found even though it takes no hierarchy-typed parameter");
    }

    @Test
    void switchCommittedToABareFieldIsTheSameProducer() {
        // state = switch (state) { ... }  — no `this.` qualifier. Spoon models the
        // bare read as a CtFieldRead with an implicit `this` target, so the two
        // spellings must be indistinguishable here.
        List<Producer> ps = producers("examples/barefield", "examples.barefield.Latch");
        assertEquals(1, ps.size());
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), commits(ps));
    }

    @Test
    void switchCommittedToAHierarchyTypedLocalIsAnAccumulator() {
        // Phase next = switch (phase) { ... };
        List<Producer> ps = producers("examples/accumulator", "examples.accumulator.Phase");
        assertEquals(1, ps.size());
        assertEquals(Set.of(CommitForm.LOCAL_ACCUMULATOR), commits(ps));
    }

    @Test
    void switchInReturnPositionIsAValueReturnProducer() {
        // return switch (state) { ... };  from a method returning the hierarchy.
        List<Producer> ps = producers("examples/http2-stream-claude", "http2.StreamState");
        assertTrue(ps.stream().anyMatch(p -> p.commit() == CommitForm.VALUE_RETURN),
                "the pure-function idiom is still recognised");
        assertTrue(ps.stream().allMatch(p -> p.commit() == CommitForm.VALUE_RETURN));
    }

    @Test
    void theProducerCarriesTheDispatchSwitchItselfNotTheHostBody() {
        // The extractor walks exactly this node. The distinction matters: the
        // field-mutation host ends with `return this.currentState;`, and walking
        // the whole body would read that trailing return as a producer with no
        // attributable source, inventing an unresolved edge out of plumbing.
        Producer p = producers("examples/http2-stream-gemini", "http2stream.State").get(0);
        assertNotNull(p.dispatch());
        assertInstanceOf(CtAbstractSwitch.class, p.dispatch());
        CtAbstractSwitch<?> sw = (CtAbstractSwitch<?>) p.dispatch();
        assertNotNull(sw.getSelector());
        assertEquals("http2stream.State", sw.getSelector().getType().getQualifiedName());
    }

    // ---- the precision guard ------------------------------------------------

    @Test
    void anExhaustiveFoldIntoAForeignCodomainIsNotAProducer() {
        // `return switch (mode) { ... }` yielding String, and
        // `label = switch (mode) { ... }` assigning String / int. Structurally a
        // dispatch over the hierarchy in an accepted position; semantically
        // ordinary pattern matching. The commit is the only thing separating them,
        // which is why it is the whole discriminator rather than a side check.
        assertEquals(List.of(), producers("examples/foreignfold", "examples.foreignfold.Mode"));
    }

    @Test
    void aCompositionalTreeRewriteIsNotAProducer() {
        // The sibling-vs-nested guard, applied on the centralized path. It is the
        // SAME predicate the carrier path uses, deliberately shared: two
        // recognizers with two notions of "recursive data type" would eventually
        // disagree, and the disagreement would be a false positive.
        assertEquals(List.of(), producers("examples/treebuilder", "treebuilder.Expr"));
    }

    // ---- F17: the chain is one spelling of the discrimination ---------------

    @Test
    void anInstanceofChainOverAFieldIsAProducer() {
        // The shape the finding is about: no switch, and no hierarchy-typed
        // parameter for the signature recognizer to key on. What makes it a
        // dispatch is the discrimination plus the commit, and both are present.
        List<Producer> ps = producers("examples/chaindispatch", "chaindispatch.Relay");
        assertEquals(1, ps.size());
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), commits(ps));
        assertInstanceOf(CtIf.class, ps.get(0).dispatch());
        assertNotNull(ps.get(0).selector());
        assertEquals("state", ps.get(0).selector().getSimpleName());
    }

    @Test
    void anInstanceofChainCommittedByReturnIsAValueReturnProducer() {
        List<Producer> ps = producers("examples/chaindispatch", "chaindispatch.Shutter");
        assertEquals(1, ps.size());
        assertEquals(Set.of(CommitForm.VALUE_RETURN), commits(ps));
        assertEquals("current", ps.get(0).selector().getSimpleName());
    }

    @Test
    void onlyTheHeadOfAChainIsAProducer() {
        // Every `else if` is itself a CtIf. Emitting one producer per link would
        // report the tail of one dispatch as a second dispatch, and the extractor
        // would walk the same branches once per link.
        assertEquals(1, producers("examples/chaindispatch", "chaindispatch.Relay").size());
    }

    @Test
    void anInstanceofFoldIntoAForeignCodomainIsNotAProducer() {
        // The instanceof spelling of foreignfold, and the pair for
        // anInstanceofChainOverAFieldIsAProducer: GlyphNamer discriminates every
        // permitted subtype and assigns a field, exactly as RelayBoard does. The
        // declared type of that field is the only difference, and it decides.
        assertEquals(List.of(), producers("examples/chaindispatch", "chaindispatch.Glyph"));
    }

    @Test
    void anInstanceofTreeRewriteIsNotAProducer() {
        // The instanceof spelling of treebuilder. Unlike treebuilder this
        // hierarchy declares no methods at all, so neither the compositional veto
        // nor the distributed recognizer can be what rejects it — the shared
        // sibling-vs-nested predicate is, and nothing else could be.
        assertEquals(List.of(), producers("examples/chaindispatch", "chaindispatch.Tree"));
    }

    @Test
    void aSingleTypeTestIsACheckNotADispatch() {
        // RelayBoard.reset() commits an H value under one type test. It is not a
        // producer, and this is a threshold with a visible price: `Tripped ->
        // Idle` is a transition the program can make and the tool does not report
        // it. Discriminating BETWEEN states is what makes a dispatch, and an `if`
        // is too common a construct to read every committing one as an automaton
        // — the same argument CarrierTransitionDetector.qualifies already makes
        // for requiring two producing subtypes.
        //
        // Asserted through the producer count: Relay has exactly one producer,
        // `accept`, so `reset` contributed none.
        List<Producer> ps = producers("examples/chaindispatch", "chaindispatch.Relay");
        assertEquals(1, ps.size());
        assertEquals("accept", ps.get(0).host().getSimpleName());
    }

    @Test
    void aChainInsideAFunctionalCallableIsLeftToTheFunctionalWalker() {
        // examples/cancellation dispatches by instanceof inside an anonymous-class
        // SAM override, which F7 discovers, attributes a selector and labels with
        // its enclosing method's name. Claiming it here as well would walk one
        // body twice — the same ownership rule findCentralizedTransitionMethods
        // already applies to anonymous-class methods.
        assertEquals(List.of(), producers("examples/cancellation",
                "examples.cancellation.CancellationRequests$CancellationState"));
    }

    // ---- F20: the nesting rejection is bounded ------------------------------

    @Test
    void armsThatCarryTheMatchedStateAreNotComposition() {
        // `case Fresh f -> new Waiting(f, 1)` hands the successor the binding the
        // arm just matched — the centralized spelling of `new Retrying(this, ...)`.
        // `case Waiting w -> ... new Waiting(w, w.misses() + 1)` adds the receiver
        // case: `w.misses()` is an int, and only the RECEIVER is a hierarchy value.
        // Both read as composition before F20, and the whole producer was
        // discarded over them.
        List<Producer> ps = producers("examples/retrystate", "retrystate.Poll");
        assertEquals(1, ps.size(), "the dispatch must survive the nesting check");
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), commits(ps));
        assertEquals("handle", ps.get(0).host().getSimpleName());
    }

    @Test
    void aFoldRebuildingTheArmItMatchedIsStillRejected() {
        // The other half of the pair, and structurally the same switch: one arm per
        // permitted subtype, each producing a hierarchy value, committed by return
        // from a hierarchy-returning method. `case Neg n -> new Neg(fold(n.operand()))`
        // differs from `case Fresh f -> new Waiting(f, 1)` in one respect — it
        // descends into a PART of the matched value instead of carrying it whole —
        // and that alone must decide rejection.
        assertEquals(List.of(), producers("examples/treebuilder", "treebuilder.Expr"));
    }

    @Test
    void aSwitchOverTheEventTypeIsNotAStateDispatch() {
        // `switch (event)` nested inside a state arm selects an input, not a
        // source state. Asking for producers of the EVENT hierarchy must find
        // none — otherwise Σ would be reported as a second automaton.
        CtModel model = modelOf("examples/eventalphabet");
        assertEquals(List.of(),
                DispatchCommitDetector.find(type(model, "examples.eventalphabet.Event"), model));
    }
}
