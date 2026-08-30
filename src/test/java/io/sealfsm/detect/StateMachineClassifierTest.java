package io.sealfsm.detect;

import io.sealfsm.model.StateMachine.Encoding;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for what counts as a <em>centralized transition function</em> — the
 * question {@link StateMachineClassifier#findCentralizedTransitionMethods} answers,
 * at the level where the decision is made rather than through the machines it
 * eventually produces.
 *
 * <p>The recognizer used to ask for a hierarchy-typed <b>parameter</b>. That admits
 * {@code H transition(H current, Event e)} and nothing else, so a driver holding
 * its own state — {@code H step(Event e)}, the same function with the state in a
 * field — matched nothing. F19 replaced the parameter with the property it was
 * standing in for: the method must <em>discriminate</em> the state.
 *
 * <p>Every case below is therefore a near-identical pair. Both halves return the
 * hierarchy type, both are declared outside the hierarchy, and each pair varies
 * exactly one thing — because "returns H" is the only signal the widened
 * recognizer has left, and it is not a sufficient one. A recognizer that got this
 * wrong would keep passing every positive integration test while walking accessors
 * and factories as standalone dispatches and reporting their results as edges out
 * of states that do not exist.
 */
class StateMachineClassifierTest {

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
        CtType<?> t = model.getElements(new TypeFilter<>(CtType.class)).stream()
                .filter(x -> x.getQualifiedName().equals(qualifiedName))
                .findFirst().orElse(null);
        assertNotNull(t, "fixture type not found: " + qualifiedName);
        return t;
    }

    /** The simple names of the methods the centralized recognizer accepts for a root. */
    private Set<String> centralizedNames(String path, String rootQualifiedName) {
        CtModel model = modelOf(path);
        List<CtMethod<?>> found = StateMachineClassifier
                .findCentralizedTransitionMethods(type(model, rootQualifiedName), model);
        return found.stream().map(CtMethod::getSimpleName).collect(Collectors.toSet());
    }

    // ---- the state as an input, however it arrives --------------------------

    @Test
    void aStatefulDriverIsACentralizedTransitionFunction() {
        // H out, no H in: the shape the parameter requirement could not express.
        // Its dispatch is a switch STATEMENT, so its result is committed per arm
        // and DispatchCommitDetector — which inspects the switch's parent — has
        // nothing to find either. Before F19 the whole hierarchy was skipped.
        assertTrue(centralizedNames("examples/statefuldriver", "statefuldriver.Sash")
                .contains("step"));
    }

    @Test
    void anAccessorAndAFactoryAreNotTransitionFunctions() {
        // The negative control, and the reason the parameter needed a replacement
        // rather than a deletion. `state()` and `initial()` are declared on the
        // same class as `step`, return the same type, and take the same (absent)
        // parameter. Only the discrimination separates them, and admitting them
        // makes the extractor walk each as a standalone dispatch: an unresolved
        // edge from a nonexistent source for the accessor, and — worse, because
        // the target resolves — a real successor attributed to one for the factory.
        Set<String> names = centralizedNames("examples/statefuldriver", "statefuldriver.Sash");
        assertFalse(names.contains("state"), "an accessor returning H is not a transition function");
        assertFalse(names.contains("initial"), "a factory returning H is not a transition function");
    }

    @Test
    void aDiscriminationIntoAForeignCodomainIsStillRejected() {
        // The other half, unchanged: `describe()` discriminates the state exactly
        // as `step` does and differs only in what it produces. Widening what counts
        // as an INPUT must not weaken what counts as a COMMIT.
        assertFalse(centralizedNames("examples/statefuldriver", "statefuldriver.Sash")
                .contains("describe"));
        assertEquals(Set.of(), centralizedNames("examples/foreignfold", "examples.foreignfold.Mode"),
                "an exhaustive fold is not a transition function whatever hosts it");
    }

    @Test
    void aHierarchyTypedParameterStillQualifiesWithoutAnyDiscrimination() {
        // The parameter is a disjunct, not a leftover: `shutOnTurn(Bolt, Event)`
        // holds no switch and no type chain over Bolt — it decides on the EVENT —
        // yet the state is plainly an input to it. Losing it would drop the F3
        // helper set, which is derived from this list, and helpers would then be
        // extracted a second time standalone.
        Set<String> names = centralizedNames("examples/factory", "examples.factory.Bolt");
        assertTrue(names.contains("shutOnTurn"), "the state is an argument, so this qualifies");
        assertFalse(names.contains("factoryOpen"),
                "same class, same codomain, no state input — the sharpest pair in the corpus");
    }

    // ---- what the widening must not reclassify ------------------------------

    @Test
    void aPerStateMethodOnTheHierarchyIsNotCentralized() {
        // A distributed transition method returns H and discriminates nothing, so
        // the widened rule would sweep it up if the "declared outside" exclusion
        // were not applied first. It would then be walked twice — once per encoding.
        assertEquals(Set.of(), centralizedNames("examples/traffic", "examples.traffic.TrafficLight"));
    }

    @Test
    void aStatefulDriverClassifiesAsCentralizedDispatch() {
        CtModel model = modelOf("examples/statefuldriver");
        var c = new StateMachineClassifier().classify(type(model, "statefuldriver.Sash"), model);
        assertTrue(c.isStateMachine());
        assertEquals(Encoding.CENTRALIZED_DISPATCH, c.encoding());
    }

    @Test
    void aPlainSumTypeIsStillRejected() {
        CtModel model = modelOf("examples/shape");
        var c = new StateMachineClassifier().classify(type(model, "examples.shape.Shape"), model);
        assertFalse(c.isStateMachine());
        assertEquals(StateMachineClassifier.Rejection.ABSTAINED, c.rejection());
    }
}
