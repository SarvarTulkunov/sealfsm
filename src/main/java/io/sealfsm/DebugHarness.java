package io.sealfsm;

import io.sealfsm.detect.SealedHierarchyDetector;
import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.detect.StateMachineClassifier.Classification;
import io.sealfsm.detect.StateMachineClassifier.Rejection;
import io.sealfsm.extract.StateExtractor;
import io.sealfsm.extract.TransitionExtractor;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import io.sealfsm.serialize.DotSerializer;
import io.sealfsm.serialize.ScxmlSerializer;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Debug harness: runs each pipeline stage individually with verbose output.
 * Set breakpoints anywhere to inspect intermediate state in IDEA's debugger.
 *
 * Usage:
 *   mvn compile
 *   # Then run this class from IDEA with program argument: examples/traffic
 *   # Or: examples/door, examples/shape, examples (all three)
 */
public class DebugHarness {

    public static void main(String[] args) {
        String src = args.length > 0 ? args[0] : "examples/traffic";
        System.out.println("═══════════════════════════════════════════════");
        System.out.println(" SealFSM debug harness — source: " + src);
        System.out.println("═══════════════════════════════════════════════\n");

        // ── STAGE 1: Spoon parse ──────────────────────────────────────
        System.out.println("▸ STAGE 1: Spoon parse");
        Launcher launcher = new Launcher();
        launcher.addInputResource(src);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        CtModel model = launcher.getModel();

        System.out.println("  Types in model:");
        for (CtType<?> type : model.getAllTypes()) {
            System.out.printf("    %-30s  sealed=%s  kind=%s%n",
                    type.getQualifiedName(),
                    SpoonCompat.isSealed(type),
                    type.getClass().getSimpleName());
        }
        System.out.println();

        // ── STAGE 1b: AST dump (set a breakpoint here to explore) ─────
        // Uncomment the block below to print the full AST of each type:
        // for (CtType<?> type : model.getAllTypes()) {
        //     System.out.println("──── AST: " + type.getQualifiedName() + " ────");
        //     System.out.println(type.toString());
        //     System.out.println();
        // }

        // ── STAGE 2: Detect sealed roots ──────────────────────────────
        System.out.println("▸ STAGE 2: Detect sealed roots");
        SealedHierarchyDetector detector = new SealedHierarchyDetector();
        List<CtType<?>> roots = detector.findSealedRoots(model);

        for (CtType<?> root : roots) {
            System.out.printf("  ROOT: %s%n", root.getQualifiedName());
            Set<CtTypeReference<?>> permitted = SpoonCompat.permittedTypes(root);
            for (CtTypeReference<?> p : permitted) {
                System.out.printf("    permits: %s%n", p.getQualifiedName());
            }
        }
        System.out.println();

        // ── STAGE 3: Classify each root ───────────────────────────────
        System.out.println("▸ STAGE 3: Classify");
        StateMachineClassifier classifier = new StateMachineClassifier();

        // Mirrors Analyzer's worklist: a root rejected by ABSTENTION releases its
        // nested sealed hierarchies as roots of their own, so the debug view shows
        // the same set of roots the pipeline actually classifies.
        Deque<CtType<?>> pending = new ArrayDeque<>(roots);
        Set<String> claimed = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            CtType<?> root = pending.pollFirst();
            if (!claimed.add(root.getQualifiedName())) continue;

            Classification c = classifier.classify(root, model);
            System.out.printf("  %-25s  isFSM=%s  encoding=%s%n",
                    root.getSimpleName(), c.isStateMachine(), c.encoding());
            System.out.printf("  %25s  reason: %s%n", "", c.reason());

            if (!c.isStateMachine()) {
                if (c.rejection() == Rejection.ABSTAINED) {
                    for (CtType<?> nested : detector.permittedSealedSubtypes(root)) {
                        if (claimed.contains(nested.getQualifiedName())) continue;
                        pending.addLast(nested);
                        System.out.printf("    re-offering nested sealed root: %s%n",
                                nested.getQualifiedName());
                    }
                }
                System.out.println("    → SKIPPED (not a state machine)\n");
                continue;
            }

            claimed.addAll(StateMachineClassifier.hierarchyQualifiedNames(root));

            // Show discovered transition methods
            List<CtMethod<?>> dist = StateMachineClassifier.findDistributedTransitionMethods(root);
            List<CtMethod<?>> cent = StateMachineClassifier.findCentralizedTransitionMethods(root, model);
            if (!dist.isEmpty()) {
                System.out.println("    Distributed methods:");
                for (CtMethod<?> m : dist) {
                    System.out.printf("      %s.%s() -> %s%n",
                            m.getDeclaringType().getSimpleName(),
                            m.getSimpleName(),
                            m.getType().getSimpleName());
                }
            }
            if (!cent.isEmpty()) {
                System.out.println("    Centralized methods:");
                for (CtMethod<?> m : cent) {
                    System.out.printf("      %s.%s(%s) -> %s%n",
                            m.getDeclaringType().getSimpleName(),
                            m.getSimpleName(),
                            m.getParameters().stream()
                                    .map(p -> p.getType().getSimpleName())
                                    .reduce((a, b) -> a + ", " + b).orElse(""),
                            m.getType().getSimpleName());
                }
            }
            System.out.println();

            // ── STAGE 4: Extract states ───────────────────────────────
            System.out.println("  ▸ STAGE 4: Extract states");
            StateExtractor stateExtractor = new StateExtractor();
            // The naming is carried into stage 5 rather than rebuilt: the debug view
            // must show the same ids the pipeline assigns, or a name collision would
            // be invisible in exactly the tool used to diagnose one.
            StateExtractor.Result extracted = stateExtractor.extract(root);
            List<State> states = extracted.topLevelStates();

            for (State s : states) {
                printState(s, "    ");
            }
            System.out.println();

            // ── STAGE 5: Extract transitions ──────────────────────────
            System.out.println("  ▸ STAGE 5: Extract transitions");
            Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
            TransitionExtractor te =
                    new TransitionExtractor(hierarchy, root.getQualifiedName(), extracted.naming());
            List<Transition> transitions = te.extract(root, model);

            for (Transition t : transitions) {
                if (t.isResolved()) {
                    System.out.printf("    %s --%s%s--> %s%n",
                            t.from(),
                            t.event() != null ? t.event() : "",
                            t.guard() != null ? " [" + t.guard() + "]" : "",
                            t.to());
                } else {
                    System.out.printf("    %s --%s%s--> ??? (unresolved: %s)%n",
                            t.from(),
                            t.event() != null ? t.event() : "",
                            t.guard() != null ? " [" + t.guard() + "]" : "",
                            t.note());
                }
            }
            if (!te.diagnostics().isEmpty()) {
                System.out.println("    Diagnostics:");
                te.diagnostics().forEach(d -> System.out.println("      " + d));
            }
            System.out.println();

            // ── STAGE 6: Assemble machine + detect initial state ──────
            System.out.println("  ▸ STAGE 6: Assemble StateMachine");
            // Run the full analyzer to get initial-state detection too
            ExtractionResult fullResult = new Analyzer().analyze(model);
            for (StateMachine machine : fullResult.machines()) {
                if (!machine.name().equals(root.getSimpleName())) continue;
                System.out.printf("    name=%s  encoding=%s  initial=%s%n",
                        machine.name(), machine.encoding(),
                        machine.initialState().orElse("(not detected)"));
                System.out.printf("    states=%d  transitions=%d  resolved=%d  unresolved=%d%n",
                        machine.allStates().size(),
                        machine.transitions().size(),
                        machine.resolvedTransitionCount(),
                        machine.unresolvedTransitionCount());
                System.out.println();

                // ── STAGE 7: Serialize ────────────────────────────────
                System.out.println("  ▸ STAGE 7: Serialize");
                System.out.println("  ── DOT ──────────────────────────");
                System.out.println(new DotSerializer().serialize(machine));
                System.out.println("  ── SCXML ────────────────────────");
                System.out.println(new ScxmlSerializer().serialize(machine));
            }
            System.out.println("─".repeat(50));
        }
    }

    private static void printState(State s, String indent) {
        System.out.printf("%s%s%s%s%n",
                indent, s.id(),
                s.isComposite() ? " (composite)" : "",
                s.isInitial() ? " ★initial" : "");
        for (State child : s.children()) {
            printState(child, indent + "  ");
        }
    }
}
