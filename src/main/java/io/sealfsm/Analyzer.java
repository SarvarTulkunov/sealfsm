package io.sealfsm;

import io.sealfsm.analyze.GuardAnalysis;
import io.sealfsm.detect.SealedHierarchyDetector;
import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.detect.StateMachineClassifier.Classification;
import io.sealfsm.extract.StateExtractor;
import io.sealfsm.extract.TransitionExtractor;
import io.sealfsm.model.CommitForm;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.StateNaming;
import io.sealfsm.model.Transition;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Orchestrates the full pipeline over a built Spoon model:
 * detect sealed roots → classify → extract states → extract transitions →
 * detect the initial state. Produces an {@link ExtractionResult} carrying both
 * the machines and the diagnostics needed for an honest validity argument.
 */
public final class Analyzer {

    private final SealedHierarchyDetector detector = new SealedHierarchyDetector();
    private final StateMachineClassifier classifier = new StateMachineClassifier();
    private final StateExtractor stateExtractor = new StateExtractor();

    public ExtractionResult analyze(CtModel model) {
        ExtractionResult result = new ExtractionResult();

        List<CtType<?>> roots = detector.findSealedRoots(model);
        if (roots.isEmpty()) {
            result.info("model", "no sealed hierarchies found");
            return result;
        }

        for (CtType<?> root : roots) {
            Classification c = classifier.classify(root, model);
            if (!c.isStateMachine()) {
                result.info(root.getQualifiedName(), "skipped — " + c.reason());
                continue;
            }

            StateMachine machine =
                    new StateMachine(root.getSimpleName(), root.getQualifiedName(), c.encoding());

            // States and the ids naming them are produced together: two permitted
            // subtypes may legally share a simple name, so ids can only be assigned
            // once the complete state set is known. The same naming then governs
            // every transition endpoint, so an edge cannot name a state that does
            // not exist — or, worse, silently merge with an edge between two other
            // states because both endpoints collapsed to one spelling.
            StateExtractor.Result states = stateExtractor.extract(root);
            for (State s : states.topLevelStates()) {
                machine.addTopLevelState(s);
            }
            for (String dup : machine.duplicateStateIds()) {
                result.warn(root.getQualifiedName(),
                        "two states share the id '" + dup + "' — their edges will be "
                                + "indistinguishable in the output");
            }
            if (states.naming().hasCollisions()) {
                result.info(root.getQualifiedName(),
                        "disambiguated state name(s) " + states.naming().collidingSimpleNames()
                                + " — the permits clause names distinct types sharing a simple name");
            }

            Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
            TransitionExtractor te =
                    new TransitionExtractor(hierarchy, root.getQualifiedName(), states.naming());
            for (Transition t : te.extract(root, model)) {
                machine.addTransition(t);
            }
            // F4: register the closed-world event alphabet Σ, so it is complete
            // even for events the transition function ignores (no edge carries them).
            for (String symbol : te.alphabet()) {
                machine.addAlphabetSymbol(symbol);
            }
            // The commit axis, orthogonal to encoding and to successor form: how
            // each recovered successor was installed. Reported so recall can be
            // stratified by idiom rather than pooled per encoding.
            for (CommitForm cf : te.commitForms()) {
                machine.addCommitForm(cf);
            }
            // A state the dispatch matched but from which nothing is produced is
            // absorbing — RFC 9113's `Closed`, whose every arm throws. A state the
            // dispatch never matched is not: that is a recall gap, and
            // markTerminalStates refuses to promote it.
            machine.markTerminalStates(te.dispatchedStates());
            for (String d : te.diagnostics()) {
                result.warn(root.getQualifiedName(), d);
            }
            // F5: guard-level defect detection over the recovered edges —
            // overlapping guards (possible nondeterminism) and numeric coverage
            // gaps (possible missing transition). Diagnostics only: no edge is
            // removed or merged, preserving the record-everything invariant.
            for (String w : GuardAnalysis.check(machine)) {
                result.warn(root.getQualifiedName(), w);
            }

            initialStateEvidence = null;
            detectInitialState(root, machine, model, states.naming(), result)
                    .ifPresentOrElse(
                            init -> {
                                machine.setInitialState(init);
                                // Name the weakest rule's evidence, so a reader can
                                // separate a proven start point from an inferred one.
                                if (initialStateEvidence != null) {
                                    result.info(root.getQualifiedName(),
                                            "initial state '" + init + "' inferred from "
                                                    + initialStateEvidence
                                                    + " — weaker evidence than a state field "
                                                    + "or an unreachable-source state");
                                }
                            },
                            () -> result.warn(root.getQualifiedName(),
                                    "initial state could not be determined"));

            result.info(root.getQualifiedName(),
                    "extracted — " + c.reason() + "; "
                            + machine.allStates().size() + " states, "
                            + machine.resolvedTransitionCount() + "/"
                            + machine.transitions().size() + " transitions resolved");
            result.addMachine(machine);
        }
        return result;
    }

    /**
     * Initial-state heuristics, in priority order:
     * <ol>
     *   <li>a field typed as the hierarchy, initialised with {@code new Concrete()},
     *       when every such field agrees on one state;</li>
     *   <li>the unique state with no resolved incoming edge but ≥1 outgoing edge;</li>
     *   <li>a hierarchy-typed LOCAL initialised with {@code new Concrete()}, when
     *       every such local in the model agrees on one state.</li>
     * </ol>
     * All are reported as heuristic; this is a known weak point and is flagged
     * as such in diagnostics when it fails.
     *
     * <p><b>No rule picks between rival candidates.</b> A model is a set of files
     * with no meaningful order, so "the first one found" is a claim about which
     * file Spoon happened to walk first, dressed up as a claim about the program.
     * Rules 1 and 3 therefore collect ALL candidates and answer only when they
     * are unanimous; a disagreement is reported and falls through to the next
     * rule. The asymmetry is intentional and runs through all three — a machine
     * with NO initial state reports a gap, whereas a wrong one is a fabricated
     * claim, and unanimity is what keeps a rule from guessing.
     *
     * <p>Rule 3 is additionally last because a local is weaker evidence than a
     * field: it is typically a driver's or a test's starting point, not
     * necessarily the machine's. It exists because a dense automaton defeats
     * rule 2 exactly when it is most faithful: in RFC 1661's LCP every state
     * including {@code Initial} has incoming edges, so "no incoming, some
     * outgoing" has no candidate at all.
     */
    private Optional<String> detectInitialState(CtType<?> root, StateMachine machine,
                                                CtModel model, StateNaming naming,
                                                ExtractionResult result) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);

        // F7: a callable that produces a state on the machine-entry residual has
        // already named the initial state explicitly (an edge from the initial
        // pseudo-state). That is a direct signal, so it wins over the heuristics.
        for (Transition t : machine.transitions()) {
            if (StateMachine.INITIAL_PSEUDO_STATE.equals(t.from())) {
                return Optional.of(StateMachine.INITIAL_PSEUDO_STATE);
            }
        }

        // Rule 1: a hierarchy-typed field seeded with a concrete state — a context
        // object's current-state holder. Unanimity is required for the same reason
        // it is at rule 3: two drivers seeded from different states are rival
        // candidates, and answering with the first one found lets file traversal
        // order decide where the machine starts.
        Set<String> seededFields = new LinkedHashSet<>();
        for (CtField<?> field : model.getElements(new TypeFilter<>(CtField.class))) {
            CtTypeReference<?> ft = field.getType();
            if (ft == null || !hierarchy.contains(ft.getQualifiedName())) continue;
            if (!(field.getDefaultExpression() instanceof CtConstructorCall<?> cc)) continue;
            CtTypeReference<?> init = cc.getType();
            if (init == null || !hierarchy.contains(init.getQualifiedName())) continue;
            if (isSelfSingleton(field, init)) continue;
            seededFields.add(naming.idFor(init.getQualifiedName()));
        }
        if (seededFields.size() == 1) {
            return Optional.of(seededFields.iterator().next());
        }
        if (seededFields.size() > 1) {
            // Sorted, not in encounter order: the decision is now independent of
            // how the model was walked, and the diagnostic reporting it should be
            // too, or the same program still yields different text run to run.
            result.warn(root.getQualifiedName(),
                    "hierarchy-typed state fields disagree on the initial state "
                            + new TreeSet<>(seededFields)
                            + " — abstaining rather than letting source order pick one; "
                            + "falling through to the structural rule");
        }

        // Rule 2: the unique state nothing reaches but something leaves.
        Set<String> hasIncoming = new HashSet<>();
        Set<String> hasOutgoing = new HashSet<>();
        for (Transition t : machine.transitions()) {
            if (t.isResolved() && t.to() != null) hasIncoming.add(t.to());
            hasOutgoing.add(t.from());
        }
        List<String> candidates = machine.allStates().stream()
                .map(State::id)
                .filter(id -> !hasIncoming.contains(id) && hasOutgoing.contains(id))
                .toList();
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }

        // Rule 3: a hierarchy-typed local seeded with a concrete state. Requires
        // unanimity across the model, so two drivers starting from different
        // states abstain rather than let source order pick a winner.
        Set<String> seeded = new LinkedHashSet<>();
        for (CtLocalVariable<?> local : model.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            CtTypeReference<?> lt = local.getType();
            if (lt == null || !hierarchy.contains(lt.getQualifiedName())) continue;
            if (!(local.getDefaultExpression() instanceof CtConstructorCall<?> cc)) continue;
            CtTypeReference<?> init = cc.getType();
            if (init == null || !hierarchy.contains(init.getQualifiedName())) continue;
            if (declaredInsideHierarchy(local, hierarchy)) continue;
            seeded.add(naming.idFor(init.getQualifiedName()));
        }
        if (seeded.size() == 1) {
            String only = seeded.iterator().next();
            // Named as the weaker evidence it is, so a reader can tell this apart
            // from a state the structure proved.
            initialStateEvidence = "a hierarchy-typed local seeded with new " + only + "()";
            return Optional.of(only);
        }
        return Optional.empty();
    }

    /**
     * A field whose initialiser constructs its own declaring type holds that
     * type's own instance, which says nothing about where the machine starts.
     * Two shapes reach rule 1 this way, and both are in the corpus:
     *
     * <ul>
     *   <li>a <b>flyweight</b> state constant, {@code static final Armed INSTANCE
     *       = new Armed()} inside {@code Armed} — every state may declare one, so
     *       counting them makes each state a rival candidate;</li>
     *   <li>an <b>enum constant</b>: Spoon models {@code Phase.IDLE} as a
     *       {@code CtEnumValue extends CtField}, typed {@code Phase}, with an
     *       implicit {@code new Phase()} default. A permitted enum is part of the
     *       hierarchy, so every one of its constants looked like a seed.</li>
     * </ul>
     *
     * <p>This exclusion is what makes rule 1's unanimity safe rather than costly.
     * Without it these outvote the one real driver and a machine whose start IS
     * stated in its source reports a gap instead — {@code rivalseeds.Ratchet} and
     * {@code examples/namecollision} both do, the latter reporting that
     * {@code [Mode, Phase, namecollision.Idle]} disagree.
     *
     * <p>Keyed on the constructed type being the DECLARING type, not merely on
     * the field living somewhere in the hierarchy: a constant declared on the
     * sealed root ({@code Signal START = new Idle();}) does name a start state,
     * and a root is abstract, so it can never be its own instance.
     */
    private static boolean isSelfSingleton(CtField<?> field, CtTypeReference<?> constructed) {
        CtType<?> owner = field.getDeclaringType();
        return owner != null && owner.getQualifiedName().equals(constructed.getQualifiedName());
    }

    /**
     * True when {@code e} sits inside one of the hierarchy's own types. A local
     * declared there is a successor under construction — {@code Signal next = new
     * Firing();} inside {@code Armed.on} — not a seed. Rule 3 rests on the local
     * being a driver's or a test's starting point, which is a claim about code
     * OUTSIDE the machine; applied inside it, the rule reports whichever
     * successor some state happens to build as the place the machine begins.
     */
    private static boolean declaredInsideHierarchy(CtElement e, Set<String> hierarchy) {
        for (CtType<?> owner = e.getParent(CtType.class); owner != null;
             owner = owner.getParent(CtType.class)) {
            if (hierarchy.contains(owner.getQualifiedName())) return true;
        }
        return false;
    }

    /**
     * Set by {@link #detectInitialState} when the initial state came from the
     * weakest rule, so the diagnostic can say what it rested on. Null when the
     * state was found structurally (or not at all).
     */
    private String initialStateEvidence;

    // Exposed for completeness checks in tests/tools.
    public boolean isSealed(CtType<?> type) {
        return SpoonCompat.isSealed(type);
    }
}
