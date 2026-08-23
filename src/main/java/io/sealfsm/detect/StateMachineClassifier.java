package io.sealfsm.detect;

import io.sealfsm.model.StateMachine.Encoding;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtReturn;
import spoon.reflect.declaration.CtAnnotation;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
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
 *   <li>The compositional veto: a hierarchy whose members are built out of one
 *       another is a recursive data type, never an automaton.</li>
 *   <li>Otherwise, structural signals, in order of specificity: methods that
 *       return a type within the hierarchy ({@code POLYMORPHIC}), a transition
 *       function taking and returning the hierarchy type, a functional callable
 *       with that signature, a switch over the hierarchy whose result is
 *       committed as a hierarchy value ({@code CENTRALIZED_DISPATCH}), or a
 *       per-state method handing the successor to a carrier
 *       ({@code POLYMORPHIC}).</li>
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

        // Precision gate, ahead of every structural signal: a hierarchy whose
        // members are composed into one another is a recursive data type, and a
        // recursive data type's "next" is a child, not a successor state. This
        // sinks tree builders, exhaustive folds and tag-dispatched deserializers
        // regardless of which recognizer would otherwise have claimed them — a
        // record component of the hierarchy type, for instance, gives such a type
        // an accessor that looks exactly like a per-state transition method.
        if (CarrierTransitionDetector.composesItself(root)) {
            return Classification.no(
                    "hierarchy members are composed into one another (a hierarchy value is a "
                            + "construction argument of another) — a recursive data type, not a state machine");
        }

        List<CtMethod<?>> distributed = findDistributedTransitionMethods(root);
        List<CtMethod<?>> centralized = findCentralizedTransitionMethods(root, model);
        // F7: a transition function need not be a named method — it may be a lambda
        // or an anonymous-class functional method with the same hierarchy-in /
        // hierarchy-out signature. These are centralized-style, so they count
        // towards CENTRALIZED_DISPATCH classification exactly as a named function would.
        List<CtElement> functional = findFunctionalTransitionCallables(root, model);
        // A switch over the hierarchy whose result is committed as a hierarchy
        // value, wherever it is hosted and however it is installed. This is the
        // widened centralized recognizer; the commit requirement is what keeps
        // exhaustive folds (switch-over-state producing a String) out.
        List<DispatchCommitDetector.Producer> producers = DispatchCommitDetector.find(root, model);

        boolean hasDist = !distributed.isEmpty();
        boolean hasCentral = !centralized.isEmpty() || !functional.isEmpty() || !producers.isEmpty();

        if (hasDist && hasCentral) {
            return Classification.yes(Encoding.MIXED,
                    "both per-state and centralized transition methods present");
        }
        if (hasCentral) {
            return Classification.yes(Encoding.CENTRALIZED_DISPATCH,
                    centralizedReason(centralized, functional, producers));
        }
        if (hasDist) {
            return Classification.yes(Encoding.POLYMORPHIC,
                    distributed.size() + " per-state transition method(s)");
        }
        // F8: nothing returns the hierarchy type, but each permitted subtype may
        // still own its transition logic and hand the successor to a *carrier*
        // (`Transition.to(new LastAck(), ...)`). Checked last, so a hierarchy the
        // existing recognizers already accept keeps its existing classification
        // and the widening can only add machines, never reclassify one.
        //
        // The result is POLYMORPHIC, not an encoding of its own: dispatch lives in
        // a per-state method either way, and only the *spelling* of the successor
        // differs — which is the orthogonal SuccessorForm axis, recorded per edge.
        if (CarrierTransitionDetector.qualifies(root)) {
            return Classification.yes(Encoding.POLYMORPHIC, carrierReason(root));
        }
        // Abstention, stated as abstention. The old wording ("looks like a plain
        // sum type") asserted a positive classification the analysis had not made:
        // a sealed type reaches this line just as readily by being the event
        // alphabet Σ, or by being dispatched somewhere the recognizers cannot see.
        return Classification.no(
                "no transition producer found (may be event/Σ type or unresolved dispatch)");
    }

    /**
     * Reason text for centralized dispatch, naming the commit form(s) so the
     * classification says which idiom was recognised rather than only that one was.
     *
     * <p>The count is of DISTINCT hosts, not the sum of the two recognizers'
     * findings. The signature-based recognizer and {@link DispatchCommitDetector}
     * legitimately overlap — a {@code H transition(H, Event)} whose body is
     * {@code return switch (current)} is seen by both — so summing them reported
     * {@code examples/door}, which has exactly one transition function, as two.
     * (Only the VALUE_RETURN-with-hierarchy-parameter shape overlapped, which is
     * the most common one.) The extraction itself was never affected: it already
     * deduped on {@code declaringType#signature}, and this uses the same key so
     * the reported number and the walked set cannot drift apart.
     */
    private static String centralizedReason(List<CtMethod<?>> centralized,
                                            List<CtElement> functional,
                                            List<DispatchCommitDetector.Producer> producers) {
        Set<String> hosts = new LinkedHashSet<>();
        for (CtMethod<?> m : centralized) hosts.add(methodKey(m));
        // A functional producer (a lambda, a method reference) has no CtMethod to
        // key on, so each counts as its own host.
        int functionalCount = functional.size();

        if (producers.isEmpty()) {
            return (hosts.size() + functionalCount) + " centralized transition function(s)";
        }
        Set<String> commits = new LinkedHashSet<>();
        for (DispatchCommitDetector.Producer p : producers) {
            commits.add(p.commit().name());
            hosts.add(methodKey(p.host()));
        }
        return (hosts.size() + functionalCount) + " centralized transition function(s), committing via "
                + String.join("/", commits);
    }

    /** Declaring type + signature: unique across the model, unlike a bare signature. */
    private static String methodKey(CtMethod<?> m) {
        if (m == null) return "?";
        CtType<?> declaring = m.getDeclaringType();
        return (declaring == null ? "?" : declaring.getQualifiedName()) + "#" + m.getSignature();
    }

    /** Reason text for a carrier-encoded machine, naming the shared method when there is one. */
    private static String carrierReason(CtType<?> root) {
        List<CtMethod<?>> carriers = CarrierTransitionDetector.findCarrierTransitionMethods(root);
        String shared = CarrierTransitionDetector.consistentMethodName(carriers);
        return carriers.size() + " carrier-based per-state transition method(s)"
                + (shared == null ? "" : " (all named '" + shared + "')");
    }

    private Encoding detectEncoding(CtType<?> root, CtModel model) {
        boolean dist = !findDistributedTransitionMethods(root).isEmpty();
        boolean central = !findCentralizedTransitionMethods(root, model).isEmpty()
                || !findFunctionalTransitionCallables(root, model).isEmpty()
                || !DispatchCommitDetector.find(root, model).isEmpty();
        if (dist && central) return Encoding.MIXED;
        if (central) return Encoding.CENTRALIZED_DISPATCH;
        if (dist) return Encoding.POLYMORPHIC;
        if (CarrierTransitionDetector.qualifies(root)) return Encoding.POLYMORPHIC;
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
            // F7: a functional method living inside an anonymous class (a supplied
            // BiFunction/Function) is a transition callable, but it is discovered
            // and walked via findFunctionalTransitionCallables so it can be
            // attributed a selector and an enclosing-method event label. Exclude it
            // here to avoid handling the same body twice.
            CtType<?> declaring = method.getDeclaringType();
            if (declaring instanceof CtClass<?> c && c.isAnonymous()) continue;
            // Exclude the per-state methods already counted as distributed:
            // a centralized function lives outside the state classes themselves.
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

    /**
     * Transition callables expressed as <em>functional values</em> rather than
     * named methods (finding F7): a lambda, or the overriding method of an
     * anonymous class. Discovery is purely by signature — a parameter whose type
     * is within the hierarchy (the state-relevant input) and a result within the
     * hierarchy — independent of the enclosing API that consumes the callable.
     *
     * <p>Each returned element is either a {@link CtMethod} (the anonymous-class
     * SAM override) or a {@link CtLambda}; the extractor threads its selector and
     * walks its body like any other centralized transition function.
     */
    public static List<CtElement> findFunctionalTransitionCallables(CtType<?> root, CtModel model) {
        Set<String> hierarchy = hierarchyQualifiedNames(root);
        List<CtElement> out = new ArrayList<>();

        // Anonymous class: new Fn() { R apply(H current, ...) { ... } }
        for (CtNewClass<?> nc : model.getElements(new TypeFilter<>(CtNewClass.class))) {
            CtClass<?> anon = nc.getAnonymousClass();
            if (anon == null) continue;
            for (CtMethod<?> m : anon.getMethods()) {
                if (hasHierarchyParam(m.getParameters(), hierarchy)
                        && isInHierarchy(m.getType(), hierarchy)) {
                    out.add(m);
                    break; // one SAM override per anonymous class
                }
            }
        }

        // Lambda: (H current, ...) -> ... producing a hierarchy value.
        for (CtLambda<?> lam : model.getElements(new TypeFilter<>(CtLambda.class))) {
            if (hasHierarchyParam(lam.getParameters(), hierarchy)
                    && lambdaProducesHierarchy(lam, hierarchy)) {
                out.add(lam);
            }
        }
        return out;
    }

    private static boolean hasHierarchyParam(List<CtParameter<?>> params, Set<String> hierarchy) {
        return params.stream().map(CtParameter::getType)
                .anyMatch(t -> isInHierarchy(t, hierarchy));
    }

    private static boolean isInHierarchy(CtTypeReference<?> t, Set<String> hierarchy) {
        return t != null && hierarchy.contains(t.getQualifiedName());
    }

    /**
     * A lambda's result is within the hierarchy when its expression body has a
     * hierarchy type, or any {@code return} in its block body produces one. Under
     * {@code noClasspath} a lambda with inferred (implicit) parameter/return types
     * may not resolve; such a callable is then simply not discovered here rather
     * than misclassified — consistent with the record-don't-guess invariant.
     */
    private static boolean lambdaProducesHierarchy(CtLambda<?> lam, Set<String> hierarchy) {
        if (lam.getExpression() != null && isInHierarchy(lam.getExpression().getType(), hierarchy)) {
            return true;
        }
        if (lam.getBody() != null) {
            for (CtReturn<?> r : lam.getBody().getElements(new TypeFilter<>(CtReturn.class))) {
                if (r.getReturnedExpression() != null
                        && isInHierarchy(r.getReturnedExpression().getType(), hierarchy)) {
                    return true;
                }
            }
        }
        return false;
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
