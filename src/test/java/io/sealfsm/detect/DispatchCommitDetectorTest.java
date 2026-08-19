package io.sealfsm.detect;

import io.sealfsm.detect.DispatchCommitDetector.Producer;
import io.sealfsm.model.CommitForm;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        CtType<?> t = model.getAllTypes().stream()
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
        assertNotNull(p.dispatch().getSelector());
        assertEquals("http2stream.State", p.dispatch().getSelector().getType().getQualifiedName());
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
