package io.sealfsm;

import io.sealfsm.analyze.GuardAnalysis;
import io.sealfsm.detect.SealedHierarchyDetector;
import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.detect.StateMachineClassifier.Classification;
import io.sealfsm.extract.StateExtractor;
import io.sealfsm.extract.TransitionExtractor;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

            for (State s : stateExtractor.extractStates(root)) {
                machine.addTopLevelState(s);
            }

            Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
            TransitionExtractor te = new TransitionExtractor(hierarchy, root.getQualifiedName());
            for (Transition t : te.extract(root, model)) {
                machine.addTransition(t);
            }
            // F4: register the closed-world event alphabet Σ, so it is complete
            // even for events the transition function ignores (no edge carries them).
            for (String symbol : te.alphabet()) {
                machine.addAlphabetSymbol(symbol);
            }
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

            detectInitialState(root, machine, model)
                    .ifPresentOrElse(
                            machine::setInitialState,
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
     *   <li>a field typed as the hierarchy, initialised with {@code new Concrete()};</li>
     *   <li>the unique state with no resolved incoming edge but ≥1 outgoing edge.</li>
     * </ol>
     * Both are reported as heuristic; this is a known weak point and is flagged
     * as such in diagnostics when it fails.
     */
    private Optional<String> detectInitialState(CtType<?> root, StateMachine machine, CtModel model) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);

        // F7: a callable that produces a state on the machine-entry residual has
        // already named the initial state explicitly (an edge from the initial
        // pseudo-state). That is a direct signal, so it wins over the heuristics.
        for (Transition t : machine.transitions()) {
            if (StateMachine.INITIAL_PSEUDO_STATE.equals(t.from())) {
                return Optional.of(StateMachine.INITIAL_PSEUDO_STATE);
            }
        }

        for (CtField<?> field : model.getElements(new TypeFilter<>(CtField.class))) {
            CtTypeReference<?> ft = field.getType();
            if (ft == null || !hierarchy.contains(ft.getQualifiedName())) continue;
            if (field.getDefaultExpression() instanceof CtConstructorCall<?> cc) {
                CtTypeReference<?> init = cc.getType();
                if (init != null && hierarchy.contains(init.getQualifiedName())) {
                    return Optional.of(init.getSimpleName());
                }
            }
        }

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
        return Optional.empty();
    }

    // Exposed for completeness checks in tests/tools.
    public boolean isSealed(CtType<?> type) {
        return SpoonCompat.isSealed(type);
    }
}
