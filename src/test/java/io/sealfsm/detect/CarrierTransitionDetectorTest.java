package io.sealfsm.detect;

import io.sealfsm.detect.CarrierTransitionDetector.Shape;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the carrier recognizer and, above all, for the
 * <em>sibling-vs-nested</em> predicate that keeps it honest.
 *
 * <p>Widening a recognizer trades recall for precision, so the two directions are
 * tested against each other on structurally near-identical inputs: {@code tcp}
 * and {@code treebuilder} both consist of a sealed root whose every permitted
 * subtype overrides one consistently-named method returning a non-hierarchy
 * carrier that wraps hierarchy values. Only the <em>position</em> of those values
 * differs — peer vs child — and that alone must decide acceptance.
 */
class CarrierTransitionDetectorTest {

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
                .flatMap(top -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(top), top.getNestedTypes().stream()))
                .filter(x -> x.getQualifiedName().equals(qualifiedName))
                .findFirst().orElse(null);
        assertNotNull(t, "fixture type not found: " + qualifiedName);
        return t;
    }

    private Shape shapeOf(CtType<?> root, String memberQualifiedName, String methodName) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        CtType<?> member = StateMachineClassifier.hierarchyTypes(root).stream()
                .filter(t -> t.getQualifiedName().equals(memberQualifiedName))
                .findFirst().orElseThrow();
        CtMethod<?> method = member.getMethods().stream()
                .filter(m -> m.getSimpleName().equals(methodName))
                .findFirst().orElseThrow();
        return CarrierTransitionDetector.shapeOf(method, hierarchy, root.getQualifiedName());
    }

    // ---- the carrier-recognition path --------------------------------------

    @Test
    void carrierMethodWrappingASiblingIsPeerShaped() {
        // return Transition.to(new LastAck(), Action.SND_FIN);
        // The successor is an ARGUMENT to a factory whose own type sits outside
        // the hierarchy — the defining shape of the carrier encoding.
        CtType<?> root = type(modelOf("examples/tcp"), "tcp.TcpState");
        assertEquals(Shape.PEER, shapeOf(root, "tcp.CloseWait", "on"));
    }

    @Test
    void everyTcpStateDeclaresACarrierTransitionMethod() {
        CtType<?> root = type(modelOf("examples/tcp"), "tcp.TcpState");
        List<CtMethod<?>> carriers = CarrierTransitionDetector.findCarrierTransitionMethods(root);

        // One per permitted subtype; `label()` (returns String) is not one.
        assertEquals(11, carriers.size(), "one carrier transition method per state");
        assertEquals("on", CarrierTransitionDetector.consistentMethodName(carriers),
                "consistent naming is a corroborating signal, discovered not assumed");
        assertTrue(CarrierTransitionDetector.qualifies(root), "TcpState is a state machine");
    }

    @Test
    void carrierRecognitionDoesNotDependOnTheMethodOrCarrierName() {
        // Nothing keys off `on` or `Transition`. The treebuilder fixture uses
        // `simplify` and `Rewrite`, and its peer-shaped method is discovered by
        // exactly the same scan — the hierarchy is then rejected on position, not
        // on naming. If naming were the signal, this would be accepted.
        CtType<?> root = type(modelOf("examples/treebuilder"), "treebuilder.Expr");
        List<CtMethod<?>> carriers = CarrierTransitionDetector.findCarrierTransitionMethods(root);
        assertTrue(carriers.stream().anyMatch(m -> m.getSimpleName().equals("simplify")),
                "an unrelated method name is discovered just the same");
        assertFalse(CarrierTransitionDetector.qualifies(root),
                "discovery is not acceptance — the guard still rejects it");
    }

    @Test
    void consistentNamingIsASignalNotARequirement() {
        // A record component of the hierarchy type synthesises an accessor that is
        // peer-shaped too (`Neg.operand()` returns the child Expr), so the carrier
        // methods of the tree builder do NOT share a name. `consistentMethodName`
        // reporting null is therefore expected, and must not be load-bearing:
        // acceptance is decided by the positional guard alone.
        CtType<?> root = type(modelOf("examples/treebuilder"), "treebuilder.Expr");
        List<CtMethod<?>> carriers = CarrierTransitionDetector.findCarrierTransitionMethods(root);
        assertNull(CarrierTransitionDetector.consistentMethodName(carriers),
                "mixed names yield no shared-name signal");
        assertFalse(CarrierTransitionDetector.qualifies(root));
    }

    // ---- the sibling-vs-nested guard: accept FSM / reject tree builder ------

    @Test
    void nestingAHierarchyValueInsideAnotherIsNestedShaped() {
        // return Rewrite.of(new Add(l.result(), r.result()), ...);
        // Same carrier shape as tcp, but the constructed Expr takes other Exprs as
        // constructor arguments: composition, not succession.
        CtType<?> root = type(modelOf("examples/treebuilder"), "treebuilder.Expr");
        assertEquals(Shape.NESTED, shapeOf(root, "treebuilder.Add", "simplify"));
        assertEquals(Shape.NESTED, shapeOf(root, "treebuilder.Neg", "simplify"));
    }

    @Test
    void oneNestedProductionVetoesTheWholeHierarchy() {
        // `Lit.simplify()` is peer-shaped in isolation (`Rewrite.unchanged(this)`),
        // so the veto must be a property of the hierarchy, not of each method:
        // otherwise a tree builder with one leaf case would be half-accepted.
        CtType<?> root = type(modelOf("examples/treebuilder"), "treebuilder.Expr");
        assertEquals(Shape.PEER, shapeOf(root, "treebuilder.Lit", "simplify"));

        assertTrue(CarrierTransitionDetector.composesItself(root),
                "a compositional hierarchy must be detected as such");
        assertFalse(CarrierTransitionDetector.qualifies(root),
                "a recursive tree builder is not a state machine");
    }

    @Test
    void aStateMachineIsNotFlaggedAsCompositional() {
        // The precision guard must not fire on the thing it is protecting:
        // `new SynReceived(true)` takes a boolean, not a TcpState.
        CtType<?> root = type(modelOf("examples/tcp"), "tcp.TcpState");
        assertFalse(CarrierTransitionDetector.composesItself(root),
                "carrying a non-hierarchy payload is not composition");
    }

    // ---- self-loops ---------------------------------------------------------

    @Test
    void selfLoopThroughTheCarrierIsAPeerProduction() {
        // return Transition.ignore(this);  — `this` is a hierarchy value handed to
        // the carrier, so the method stays peer-shaped rather than falling to NONE
        // and losing the state's default edge.
        CtType<?> root = type(modelOf("examples/tcp"), "tcp.TcpState");
        assertEquals(Shape.PEER, shapeOf(root, "tcp.FinWait2", "on"));
        assertEquals(Shape.PEER, shapeOf(root, "tcp.LastAck", "on"));
    }

    @Test
    void selfProductionAloneDoesNotMakeAnAutomaton() {
        // `Lit.simplify()` only ever rebuilds itself. A hierarchy whose members
        // never name a sibling has no edges between states, so the cross-state
        // requirement — not just the nesting test — is doing work here.
        CtType<?> root = type(modelOf("examples/treebuilder"), "treebuilder.Expr");
        assertFalse(CarrierTransitionDetector.qualifies(root));
    }

    // ---- non-transition shapes ---------------------------------------------

    @Test
    void aPlainSumTypeProducesNoTransitionMethods() {
        // `double area()` produces no hierarchy value at all.
        CtType<?> root = type(modelOf("examples/shape"), "examples.shape.Shape");
        assertEquals(Shape.NONE, shapeOf(root, "examples.shape.Circle", "area"));
        assertTrue(CarrierTransitionDetector.findCarrierTransitionMethods(root).isEmpty());
        assertFalse(CarrierTransitionDetector.qualifies(root));
    }

    @Test
    void anEventAlphabetIsNotAStateMachine() {
        // `tcp.Event` is Σ. It is sealed and sits next to a real machine, so it is
        // the likeliest false positive of the widened recognizer.
        CtType<?> root = type(modelOf("examples/tcp"), "tcp.Event");
        assertTrue(CarrierTransitionDetector.findCarrierTransitionMethods(root).isEmpty());
        assertFalse(CarrierTransitionDetector.qualifies(root));
    }

    @Test
    void aVoidMutatorIsNotACarrierMethod() {
        // The GoF mutation encoding (`ctx.setState(new Locked())`) passes a
        // hierarchy value to a call too, but returns nothing. It belongs to the
        // mutation path, and the two recognizers must not both claim it.
        CtType<?> root = type(modelOf("examples/gofcontext"), "examples.gofcontext.Portal");
        assertEquals(Shape.NONE, shapeOf(root, "examples.gofcontext.Open", "handle"));
        assertTrue(CarrierTransitionDetector.findCarrierTransitionMethods(root).isEmpty());
    }
}
