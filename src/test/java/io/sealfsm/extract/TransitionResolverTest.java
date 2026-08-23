package io.sealfsm.extract;

import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.extract.TransitionResolver.Candidate;
import io.sealfsm.model.SuccessorForm;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the successor-resolution sub-procedure, expression shape by
 * expression shape.
 *
 * <p>This is the axis orthogonal to the encoding: the same resolver runs under
 * centralized and per-state dispatch alike, so its behaviour is pinned here on
 * individual expressions rather than only end-to-end. The cases that matter most
 * are the ones where the <em>declared type</em> and the <em>value</em> disagree —
 * a singleton typed as the abstract root, an enum constant whose declared type is
 * the enum — because that is exactly where a resolver silently invents an edge.
 */
class TransitionResolverTest {

    private CtModel model;

    private CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    private CtType<?> type(CtModel m, String qualifiedName) {
        CtType<?> t = m.getAllTypes().stream()
                .filter(x -> x.getQualifiedName().equals(qualifiedName))
                .findFirst().orElse(null);
        assertNotNull(t, "fixture type not found: " + qualifiedName);
        return t;
    }

    private TransitionResolver resolverFor(String rootQualifiedName) {
        CtType<?> root = type(model, rootQualifiedName);
        return new TransitionResolver(
                StateMachineClassifier.hierarchyQualifiedNames(root), root.getQualifiedName());
    }

    /**
     * The {@code n}-th returned expression of a method, unwrapped from the carrier
     * when there is one — i.e. the expression the extractor hands the resolver.
     */
    private CtExpression<?> returnedValue(String typeQn, String method, int index) {
        CtMethod<?> m = type(model, typeQn).getMethods().stream()
                .filter(x -> x.getSimpleName().equals(method))
                .findFirst().orElseThrow();
        List<? extends CtReturn<?>> returns = m.getBody().getElements(new TypeFilter<>(CtReturn.class));
        CtExpression<?> returned = returns.get(index).getReturnedExpression();
        // Unwrap `Step.to(X)` / `Step.stay(X)` to its single argument.
        if (returned instanceof spoon.reflect.code.CtInvocation<?> inv && !inv.getArguments().isEmpty()) {
            return inv.getArguments().get(0);
        }
        return returned;
    }

    private Candidate only(List<Candidate> candidates) {
        assertEquals(1, candidates.size(), "expected exactly one candidate: " + candidates);
        return candidates.get(0);
    }

    // ---- construction and self ---------------------------------------------

    @Test
    void constructionResolvesToTheConstructedSubtype() {
        model = modelOf("examples/tcp");
        TransitionResolver r = resolverFor("tcp.TcpState");
        // return Transition.to(new Listen(), Action.CREATE_TCB);
        Candidate c = only(r.resolve(returnedValue("tcp.Closed", "on", 0), "Closed"));
        assertTrue(c.resolved());
        assertEquals("Listen", c.targetSimpleName());
        assertEquals(SuccessorForm.CONSTRUCTION, c.form());
    }

    @Test
    void thisResolvesToTheFromStateAsASelfLoop() {
        model = modelOf("examples/tcp");
        TransitionResolver r = resolverFor("tcp.TcpState");
        // return Transition.ignore(this);   — the last return of Closed.on
        Candidate c = only(r.resolve(returnedValue("tcp.Closed", "on", 2), "Closed"));
        assertTrue(c.resolved());
        assertEquals("Closed", c.targetSimpleName());
        assertEquals(SuccessorForm.SELF, c.form());
    }

    @Test
    void thisWithoutAKnownFromStateIsUnresolvedRatherThanInvented() {
        model = modelOf("examples/tcp");
        TransitionResolver r = resolverFor("tcp.TcpState");
        Candidate c = only(r.resolve(returnedValue("tcp.Closed", "on", 2), null));
        assertFalse(c.resolved(), "a self-loop with no known source cannot be fabricated");
    }

    // ---- singletons: declared type vs value --------------------------------

    @Test
    void singletonTypedAsTheConcreteStateResolvesFromItsDeclaredType() {
        model = modelOf("examples/valueforms");
        TransitionResolver r = resolverFor("valueforms.Signal");
        // return Step.to(Armed.INSTANCE);   where INSTANCE is `static final Armed`
        Candidate c = only(r.resolve(returnedValue("valueforms.Idle", "on", 0), "Idle"));
        assertTrue(c.resolved());
        assertEquals("Armed", c.targetSimpleName());
        assertEquals(SuccessorForm.SINGLETON_FIELD, c.form());
    }

    @Test
    void singletonTypedAsTheRootResolvesThroughItsInitializer() {
        // The regression this guards: `static final Signal INSTANCE = new Idle();`
        // is declared as the ABSTRACT ROOT. Reading the declared type says only
        // "some state", and the old rule "root-typed read means stay put" then
        // emitted a confident self-loop — a wrong edge, silently resolved. The
        // identity has to come from the initializer.
        model = modelOf("examples/valueforms");
        TransitionResolver r = resolverFor("valueforms.Signal");
        // return Step.to(Idle.INSTANCE);   — first return of Firing.on
        Candidate c = only(r.resolve(returnedValue("valueforms.Firing", "on", 0), "Firing"));
        assertTrue(c.resolved());
        assertEquals("Idle", c.targetSimpleName(), "must be Idle, not a self-loop to Firing");
        assertEquals(SuccessorForm.SINGLETON_FIELD, c.form());
    }

    // ---- enum constants -----------------------------------------------------

    @Test
    void enumConstantResolvesToTheConstantNotToTheEnumType() {
        model = modelOf("examples/valueforms");
        TransitionResolver r = resolverFor("valueforms.Signal");
        // return Step.to(Phase.RAMP);   — the constant IS the state, not `Phase`.
        Candidate c = only(r.resolve(returnedValue("valueforms.Armed", "on", 1), "Armed"));
        assertTrue(c.resolved());
        assertEquals("RAMP", c.targetSimpleName());
        assertEquals(SuccessorForm.ENUM_CONSTANT, c.form());
    }

    @Test
    void bareEnumConstantInsideItsOwnEnumResolves() {
        model = modelOf("examples/valueforms");
        TransitionResolver r = resolverFor("valueforms.Signal");
        // return Step.to(PEAK);   — unqualified, read from inside Phase itself.
        Candidate c = only(r.resolve(returnedValue("valueforms.Phase", "on", 0), "Phase"));
        assertTrue(c.resolved());
        assertEquals("PEAK", c.targetSimpleName());
        assertEquals(SuccessorForm.ENUM_CONSTANT, c.form());
    }

    // ---- locals -------------------------------------------------------------

    @Test
    void localHoldingAHierarchyValueResolvesIntraProcedurally() {
        model = modelOf("examples/valueforms");
        TransitionResolver r = resolverFor("valueforms.Signal");
        // Signal next = new Firing();  return Step.to(next);
        Candidate c = only(r.resolve(returnedValue("valueforms.Armed", "on", 0), "Armed"));
        assertTrue(c.resolved());
        assertEquals("Firing", c.targetSimpleName());
        assertEquals(SuccessorForm.LOCAL_VARIABLE, c.form());
    }

    // ---- the scope line -----------------------------------------------------

    @Test
    void aHelperComputedSuccessorIsUnresolved() {
        model = modelOf("examples/valueforms");
        TransitionResolver r = resolverFor("valueforms.Signal");
        // return Step.to(Router.pick(tick));
        Candidate c = only(r.resolve(returnedValue("valueforms.Firing", "on", 1), "Firing"));
        assertFalse(c.resolved(), "leaving the method is out of scope — record, do not guess");
        assertNotNull(c.raw(), "the unresolved edge keeps the offending source text");
    }

    @Test
    void theRootTypedDispatchSelectorStillMeansStayPut() {
        // The complement of the singleton case: a root-typed PARAMETER is the value
        // being dispatched on, so reading it does mean "stay in the matched state".
        // Narrowing the root-typed rule to selectors must not break this.
        model = modelOf("examples/door");
        TransitionResolver r = resolverFor("examples.door.Door");
        CtMethod<?> transition = type(model, "examples.door.DoorMachine").getMethods().stream()
                .filter(m -> m.getSimpleName().equals("transition"))
                .findFirst().orElseThrow();
        CtExpression<?> selectorRead = transition.getElements(new TypeFilter<>(CtExpression.class))
                .stream()
                .filter(e -> e instanceof spoon.reflect.code.CtVariableAccess<?> va
                        && va.getVariable() != null
                        && "current".equals(va.getVariable().getSimpleName()))
                .findFirst().orElseThrow();

        Candidate c = only(r.resolve(selectorRead, "Locked"));
        assertTrue(c.resolved());
        assertEquals("Locked", c.targetSimpleName());
        assertEquals(SuccessorForm.SELF, c.form());
    }

    @Test
    void hierarchyStatesAreNeverConfusedWithForeignTypes() {
        model = modelOf("examples/valueforms");
        TransitionResolver r = resolverFor("valueforms.Signal");
        Set<String> states = Set.of("Idle", "Armed", "Firing", "Phase", "RAMP", "PEAK");
        for (String owner : List.of("valueforms.Idle", "valueforms.Armed",
                "valueforms.Firing", "valueforms.Phase")) {
            CtType<?> ownerType = type(model, owner);
            String from = ownerType.getSimpleName();
            CtMethod<?> m = ownerType.getMethods().stream()
                    .filter(x -> x.getSimpleName().equals("on")).findFirst().orElseThrow();
            for (CtReturn<?> ret : m.getBody().getElements(new TypeFilter<>(CtReturn.class))) {
                CtExpression<?> value = ret.getReturnedExpression();
                if (!(value instanceof spoon.reflect.code.CtInvocation<?> inv)
                        || inv.getArguments().isEmpty()) {
                    continue;
                }
                for (Candidate c : r.resolve(inv.getArguments().get(0), from)) {
                    if (c.resolved()) {
                        assertTrue(states.contains(c.targetSimpleName()),
                                "resolved to a non-state: " + c.targetSimpleName());
                    }
                }
            }
        }
    }

    // ---- F13: reassignment is a property of a VARIABLE, not of a name -------

    /**
     * The three arm-locals of {@code GateDriver.transition}, keyed by the source
     * text of their initializer. Java scopes each to its own arm block, so they
     * are three distinct variables that merely share the name {@code next}.
     */
    private CtLocalVariable<?> armLocal(String initializerText) {
        CtMethod<?> m = type(model, "scopedlocals.GateDriver").getMethods().stream()
                .filter(x -> x.getSimpleName().equals("transition"))
                .findFirst().orElseThrow();
        List<CtLocalVariable<?>> decls = m.getElements(new TypeFilter<CtLocalVariable<?>>(CtLocalVariable.class))
                .stream()
                .filter(v -> v.getSimpleName().equals("next"))
                .filter(v -> v.getDefaultExpression() != null
                        && v.getDefaultExpression().toString().equals(initializerText))
                .toList();
        assertEquals(1, decls.size(),
                "fixture must declare exactly one `next` initialised to " + initializerText);
        return decls.get(0);
    }

    /**
     * A read of {@code decl}, found by SCOPE rather than by
     * {@code getDeclaration()} — the very mechanism under test must not be what
     * selects the input. Java forbids a local shadowing a local, so any read of
     * {@code next} inside the declaring block is a read of that {@code next}.
     */
    private CtVariableAccess<?> readOf(CtLocalVariable<?> decl) {
        CtBlock<?> scope = decl.getParent(CtBlock.class);
        assertNotNull(scope, "declaration must sit in a block");
        List<CtVariableRead<?>> reads =
                scope.getElements(new TypeFilter<CtVariableRead<?>>(CtVariableRead.class)).stream()
                        .filter(v -> v.getVariable() != null
                                && v.getVariable().getSimpleName().equals(decl.getSimpleName()))
                        .toList();
        assertFalse(reads.isEmpty(), "expected a read of `" + decl.getSimpleName() + "` in its block");
        return reads.get(reads.size() - 1);
    }

    @Test
    void reassignmentIsDecidedPerVariableNotPerName() {
        // The bug this pins: `isReassigned` scanned the whole method for a write
        // to SOME variable spelled `next` and answered a question about the name.
        // A centralized dispatch is where that misfires, because every arm is its
        // own block and every arm calls its successor `next` — so one arm's
        // accumulator condemned all three, and the reads that were single
        // assignment lost their resolution.
        model = modelOf("examples/scopedlocals");

        CtLocalVariable<?> single = armLocal("new scopedlocals.Ajar()");
        CtLocalVariable<?> accumulated = armLocal("current");
        CtLocalVariable<?> guarded = armLocal("new scopedlocals.Shut()");

        assertFalse(TransitionResolver.isReassigned(readOf(single).getVariable()),
                "a local written nowhere is not reassigned because a SIBLING arm's local is");
        assertFalse(TransitionResolver.isReassigned(readOf(guarded).getVariable()),
                "nor is the one in the third arm");

        // NEGATIVE CONTROL. This one genuinely is reassigned, and narrowing the
        // test by identity must not lose that: `next` starts as the root-typed
        // selector `current`, so a "not reassigned" answer here would resolve its
        // initializer to a confident, unguarded self-loop and drop the real
        // `new Wedged()` target. That is finding F1, and it is the error this fix
        // is one sign away from reintroducing.
        assertTrue(TransitionResolver.isReassigned(readOf(accumulated).getVariable()),
                "a local this method really does write must still read as reassigned");
    }

    @Test
    void aSingleAssignmentLocalResolvesThroughItsInitializer() {
        // The consequence at the resolver: the read is resolvable, and it is
        // resolvable AS a local — the successor-form axis reports how the value
        // was spelled, so degrading this to the extractor's reaching-definitions
        // fallback would relabel it CONSTRUCTION and misattribute the form.
        model = modelOf("examples/scopedlocals");
        TransitionResolver r = resolverFor("scopedlocals.Gate");

        Candidate c = only(r.resolve(readOf(armLocal("new scopedlocals.Ajar()")), "Shut"));
        assertTrue(c.resolved());
        assertEquals("Ajar", c.targetSimpleName());
        assertEquals(SuccessorForm.LOCAL_VARIABLE, c.form());
    }

    @Test
    void aReassignedRootTypedLocalIsNeverResolvedFromItsInitializer() {
        // The F1 guarantee, restated on this fixture: `Gate next = current;` is
        // reassigned below, so the resolver must refuse both the initializer rule
        // and the root-typed-read shortcut and hand back an unresolved candidate.
        // The extractor's reaching-definitions pass is what recovers the two real
        // targets; a resolved SELF here would fabricate an edge and hide one.
        model = modelOf("examples/scopedlocals");
        TransitionResolver r = resolverFor("scopedlocals.Gate");

        Candidate c = only(r.resolve(readOf(armLocal("current")), "Ajar"));
        assertFalse(c.resolved(), "a reassigned root-typed local must not resolve to a self-loop");
    }
}
