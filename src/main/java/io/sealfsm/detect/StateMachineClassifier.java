package io.sealfsm.detect;

import io.sealfsm.model.StateMachine.Encoding;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a sealed root is a state machine and, if so, how its
 * transitions are encoded. This classifier is where the tool's
 * <em>false positives</em> live — a plain sum type such as
 * {@code sealed interface Shape permits Circle, Square} must be rejected — so
 * it is treated as a first-class sub-problem rather than an afterthought.
 *
 * <p>Decision order:
 * <ol>
 *   <li>An explicit {@code @Fsm}/{@code @FSM}/{@code @StateMachine} annotation
 *       on the root is decisive (opt-in / ground-truth pinning).</li>
 *   <li>Otherwise, structural signals: methods that return a type within the
 *       hierarchy ({@code DISTRIBUTED}), and/or a transition function taking and
 *       returning the hierarchy type ({@code CENTRALIZED}).</li>
 * </ol>
 */
public final class StateMachineClassifier {

    private static final Set<String> MARKER_NAMES = Set.of("Fsm", "FSM", "StateMachine");

    public record Classification(boolean isStateMachine, Encoding encoding, String reason) {
        public static Classification no(String reason) {
            return new Classification(false, Encoding.MIXED, reason);
        }
        public static Classification yes(Encoding enc, String reason) {
            return new Classification(true, enc, reason);
        }
    }

    public Classification classify(CtType<?> root, CtModel model) {
        if (hasMarkerAnnotation(root)) {
            return Classification.yes(detectEncoding(root, model),
                    "explicit @Fsm/@StateMachine marker");
        }

        List<CtMethod<?>> distributed = findDistributedTransitionMethods(root);
        List<CtMethod<?>> centralized = findCentralizedTransitionMethods(root, model);

        boolean hasDist = !distributed.isEmpty();
        boolean hasCentral = !centralized.isEmpty();

        if (hasDist && hasCentral) {
            return Classification.yes(Encoding.MIXED,
                    "both per-state and centralized transition methods present");
        }
        if (hasCentral) {
            return Classification.yes(Encoding.CENTRALIZED,
                    centralized.size() + " centralized transition function(s)");
        }
        if (hasDist) {
            return Classification.yes(Encoding.DISTRIBUTED,
                    distributed.size() + " per-state transition method(s)");
        }
        return Classification.no(
                "sealed type has no methods returning the hierarchy type — looks like a plain sum type");
    }

    private Encoding detectEncoding(CtType<?> root, CtModel model) {
        boolean dist = !findDistributedTransitionMethods(root).isEmpty();
        boolean central = !findCentralizedTransitionMethods(root, model).isEmpty();
        if (dist && central) return Encoding.MIXED;
        if (central) return Encoding.CENTRALIZED;
        if (dist) return Encoding.DISTRIBUTED;
        return Encoding.MIXED; // annotated but shapeless; extractor will report gaps
    }

    // ---- shared discovery, reused by the extractor ----------------------------

    /**
     * Methods declared on the root or any permitted subtype whose return type is
     * inside the hierarchy. These produce the next state in the State pattern.
     */
    public static List<CtMethod<?>> findDistributedTransitionMethods(CtType<?> root) {
        Set<String> hierarchy = hierarchyQualifiedNames(root);
        List<CtMethod<?>> out = new ArrayList<>();
        for (CtType<?> member : hierarchyTypes(root)) {
            for (CtMethod<?> method : member.getMethods()) {
                CtTypeReference<?> ret = method.getType();
                if (ret != null && hierarchy.contains(ret.getQualifiedName())) {
                    out.add(method);
                }
            }
        }
        return out;
    }

    /**
     * Methods anywhere in the model that both take a hierarchy-typed parameter
     * and return a hierarchy type: the {@code T transition(T current, Event e)}
     * shape idiomatic to modern sealed-type FSMs.
     */
    public static List<CtMethod<?>> findCentralizedTransitionMethods(CtType<?> root, CtModel model) {
        Set<String> hierarchy = hierarchyQualifiedNames(root);
        Set<String> hierarchyTypeNames = new LinkedHashSet<>();
        for (CtType<?> t : hierarchyTypes(root)) hierarchyTypeNames.add(t.getQualifiedName());

        List<CtMethod<?>> out = new ArrayList<>();
        for (CtMethod<?> method : model.getElements(new TypeFilter<>(CtMethod.class))) {
            CtTypeReference<?> ret = method.getType();
            if (ret == null || !hierarchy.contains(ret.getQualifiedName())) continue;
            // Exclude the per-state methods already counted as distributed:
            // a centralized function lives outside the state classes themselves.
            CtType<?> declaring = method.getDeclaringType();
            boolean declaredInsideHierarchy =
                    declaring != null && hierarchyTypeNames.contains(declaring.getQualifiedName());
            boolean takesHierarchyParam = method.getParameters().stream()
                    .map(CtParameter::getType)
                    .anyMatch(p -> p != null && hierarchy.contains(p.getQualifiedName()));
            if (takesHierarchyParam && !declaredInsideHierarchy) {
                out.add(method);
            }
        }
        return out;
    }

    // ---- hierarchy helpers ----------------------------------------------------

    /** Root plus all (transitively) permitted subtypes, as resolved CtTypes. */
    public static List<CtType<?>> hierarchyTypes(CtType<?> root) {
        List<CtType<?>> out = new ArrayList<>();
        collectHierarchy(root, out, new LinkedHashSet<>());
        return out;
    }

    public static Set<String> hierarchyQualifiedNames(CtType<?> root) {
        Set<String> names = new LinkedHashSet<>();
        for (CtType<?> t : hierarchyTypes(root)) names.add(t.getQualifiedName());
        return names;
    }

    private static void collectHierarchy(CtType<?> type, List<CtType<?>> out, Set<String> seen) {
        if (type == null || !seen.add(type.getQualifiedName())) return;
        out.add(type);
        for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(type)) {
            CtType<?> decl = ref.getTypeDeclaration();
            if (decl != null) collectHierarchy(decl, out, seen);
        }
    }

    private boolean hasMarkerAnnotation(CtType<?> root) {
        for (CtAnnotation<?> ann : root.getAnnotations()) {
            CtTypeReference<?> annType = ann.getAnnotationType();
            if (annType != null && MARKER_NAMES.contains(annType.getSimpleName())) {
                return true;
            }
        }
        return false;
    }
}
