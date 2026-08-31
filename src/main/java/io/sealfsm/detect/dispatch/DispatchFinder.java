package io.sealfsm.detect.dispatch;

import io.sealfsm.detect.CarrierTransitionDetector;
import io.sealfsm.detect.DispatchCommitDetector;
import io.sealfsm.detect.StateMachineClassifier;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The four finders, each answering only the FIRST axis: <em>where is the current
 * state discriminated, and into which branches?</em>
 *
 * <p>Not one of them asks what the branches do with the successor they choose.
 * That is {@link CommitClassifier}'s question, asked once, at any locus. Before
 * this split each recognizer hard-coded a locus and a commit form as a fused
 * pair, and the pairing was invisible in the code — it read as one predicate —
 * which is why the missing combinations were never noticed as missing:
 *
 * <pre>
 *   CarrierTransitionDetector          = per-subtype override ∧ carrier return
 *   DispatchCommitDetector             = switch over H        ∧ {return, field, local}
 *   findCentralizedTransitionMethods   = switch over H        ∧ return ∧ H-typed parameter
 *   findFunctionalTransitionCallables  = lambda selector      ∧ return
 * </pre>
 *
 * <p>A centralized switch whose arms return a carrier wrapping the next state
 * satisfies both halves of a valid pair and no recognizer above: the carrier one
 * insists on a per-subtype override, the switch one insists the commit be an H
 * value. Composing the axes makes it reachable without a fifth recognizer.
 *
 * <p><b>The finders wrap the existing recognizers rather than reimplementing
 * them.</b> "What is a per-state transition method" and "what is a
 * discrimination" are settled questions with fixtures and tests behind them; a
 * second implementation of either would be a second notion of it, which is what
 * this codebase refuses everywhere else. What is new is that the answer now has a
 * type, and that the commit is no longer folded into it.
 */
public final class DispatchFinder {

    private DispatchFinder() {
    }

    /**
     * The discovery routes, kept apart.
     *
     * <p>Not one flat list, because the routes are not interchangeable to a
     * reader: "4 per-state transition methods" and "2 centralized transition
     * functions committing via FIELD_MUTATION" are different reports of different
     * evidence, and the classification reason names which. They ARE
     * interchangeable to the walker, which is what {@link #all()} is for.
     */
    public record Sites(List<DispatchSite> overrides, List<DispatchSite> carriers,
                        List<DispatchSite> centralized, List<DispatchSite> producers,
                        List<DispatchSite> functional) {

        public List<DispatchSite> all() {
            List<DispatchSite> out = new ArrayList<>();
            out.addAll(overrides);
            out.addAll(centralized);
            out.addAll(functional);
            out.addAll(producers);
            out.addAll(carriers);
            return out;
        }

        public boolean isEmpty() {
            return overrides.isEmpty() && carriers.isEmpty() && centralized.isEmpty()
                    && producers.isEmpty() && functional.isEmpty();
        }
    }

    /** Every dispatch site for {@code root}, by discovery route. */
    public static Sites find(CtType<?> root, CtModel model) {
        return new Sites(
                overrideSites(root),
                carrierSites(root),
                centralizedMethodSites(root, model),
                producerSites(root, model),
                functionalSites(root, model));
    }

    // ---- POLYMORPHIC_OVERRIDE -------------------------------------------------

    /**
     * Dispatch by virtual call: one arm per permitted subtype declaring the
     * transition method, {@code fromSimpleName} the declaring subtype.
     *
     * <p>The from-state here is <em>exact</em> and needs no data flow — it is the
     * declaring class. That is the one thing this locus has that no other does,
     * and it is why an override site's arms never carry a null from-state.
     *
     * <p>Merged with {@link #carrierSites} at the classifier's level and kept
     * separate here, because the two report differently ("per-state transition
     * method" vs "carrier-based per-state transition method") and a hierarchy may
     * legitimately have both.
     */
    public static List<DispatchSite> overrideSites(CtType<?> root) {
        return sitesForMethods(StateMachineClassifier.findDistributedTransitionMethods(root));
    }

    /**
     * The same locus, reached by a method that returns a <em>carrier</em> wrapping
     * the successor rather than the hierarchy type itself. A different commit
     * ({@link io.sealfsm.model.CommitForm#POLY_CARRIER}), not a different dispatch:
     * the state is still discriminated by which subtype's override runs.
     */
    public static List<DispatchSite> carrierSites(CtType<?> root) {
        return sitesForMethods(CarrierTransitionDetector.findCarrierTransitionMethods(root));
    }

    private static List<DispatchSite> sitesForMethods(List<CtMethod<?>> methods) {
        List<DispatchSite> out = new ArrayList<>();
        for (CtMethod<?> m : methods) {
            CtType<?> declaring = m.getDeclaringType();
            if (declaring == null) continue;
            DispatchArm arm = DispatchArm.of(declaring.getSimpleName(), m.getBody());
            out.add(new DispatchSite(DispatchLocus.POLYMORPHIC_OVERRIDE, m, m, List.of(arm)));
        }
        return out;
    }

    // ---- CENTRALIZED_SWITCH ---------------------------------------------------

    /**
     * A named method outside the hierarchy that produces a hierarchy value and
     * examines one — the centralized transition function found by its signature.
     *
     * <p>Its arms are not enumerable from the signature: the discrimination may be
     * a switch, a chain, or several, and the whole body is the transition relation
     * when the state is an ARGUMENT (see
     * {@link StateMachineClassifier#takesHierarchyParameter}). So the site carries
     * the body as its single, unattributed arm and the walker resolves the
     * from-states as it goes. A null {@code fromSimpleName} here means exactly what
     * the type says it means: reached in more than one state, or in one not yet
     * determined.
     */
    public static List<DispatchSite> centralizedMethodSites(CtType<?> root, CtModel model) {
        List<DispatchSite> out = new ArrayList<>();
        for (CtMethod<?> m : StateMachineClassifier.findCentralizedTransitionMethods(root, model)) {
            DispatchArm arm = new DispatchArm(null, m.getBody(), null, null);
            out.add(new DispatchSite(DispatchLocus.CENTRALIZED_SWITCH, m, m, List.of(arm)));
        }
        return out;
    }

    /**
     * Every {@code switch} over the hierarchy in the model, and every
     * {@code instanceof} chain over one, <b>without asking what it commits</b> —
     * the locus half of what {@link DispatchCommitDetector#find} answers as a
     * fused pair.
     *
     * <p>Everything excluded here is a statement about the LOCUS, never about the
     * commit: a discrimination inside a lambda belongs to
     * {@link DispatchLocus#FUNCTIONAL_CALLABLE} (one body, one walker), and a
     * discrimination with no enclosing method is an initializer rather than a
     * transition function.
     */
    public static List<DispatchSite> switchSites(CtType<?> root, CtModel model) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        List<DispatchSite> out = new ArrayList<>();
        for (CtSwitchExpression<?, ?> sw : model.getElements(new TypeFilter<>(CtSwitchExpression.class))) {
            addSwitchSite(sw, hierarchy, out);
        }
        for (CtSwitch<?> sw : model.getElements(new TypeFilter<>(CtSwitch.class))) {
            addSwitchSite(sw, hierarchy, out);
        }
        return out;
    }

    private static void addSwitchSite(CtAbstractSwitch<?> sw, Set<String> hierarchy,
                                      List<DispatchSite> out) {
        if (!dispatchesOnHierarchy(sw, hierarchy)) return;
        if (insideFunctionalCallable(sw)) return;
        CtMethod<?> host = enclosingMethod(sw);
        if (host == null) return;
        out.add(new DispatchSite(DispatchLocus.CENTRALIZED_SWITCH, host, sw,
                armsOf(sw, hierarchy)));
    }

    /**
     * One arm per {@code case}, with the from-state read from the arm's matched
     * type pattern through the single shared {@link CasePatterns} helper — the
     * same reading the walker will do, so the recognizer and the extractor cannot
     * grow two notions of which state an arm matched.
     *
     * <p>The name recorded is the type's <em>simple</em> name. Downstream it is
     * re-derived through {@link io.sealfsm.model.StateNaming}, which is the only
     * thing entitled to turn a qualified name into a state id (two permitted types
     * may legally share a simple name). Nothing here is used as an endpoint.
     */
    private static List<DispatchArm> armsOf(CtAbstractSwitch<?> sw, Set<String> hierarchy) {
        List<DispatchArm> arms = new ArrayList<>();
        for (CtCase<?> c : sw.getCases()) {
            CtTypeReference<?> pattern = null;
            try {
                for (CtExpression<?> label : c.getCaseExpressions()) {
                    CtTypeReference<?> t = CasePatterns.patternType(label);
                    if (t != null && hierarchy.contains(t.getQualifiedName())) {
                        pattern = t;
                        break;
                    }
                }
            } catch (Throwable ignored) {
                // an unreadable label leaves the arm unattributed, which is the
                // honest answer and the one the walker already reports.
            }
            arms.add(new DispatchArm(pattern == null ? null : pattern.getSimpleName(), c,
                    null, null));
        }
        return arms;
    }

    /**
     * The dispatches whose result is committed as a hierarchy value: the switch
     * and chain sites that survive {@link CommitClassifier} and
     * {@link CompositionVeto}.
     *
     * <p>Delegated to {@link DispatchCommitDetector#find} rather than recomposed
     * here, because that method IS the composition — locus, then commit, then
     * veto, in that order — and it is what the extractor and the tests already
     * consume. The finder above supplies the first of those three; this is the
     * whole answer.
     */
    public static List<DispatchSite> producerSites(CtType<?> root, CtModel model) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        List<DispatchSite> out = new ArrayList<>();
        for (DispatchCommitDetector.Producer p : DispatchCommitDetector.find(root, model)) {
            CtElement node = p.dispatch();
            DispatchLocus locus = node instanceof CtIf
                    ? DispatchLocus.INSTANCEOF_CHAIN : DispatchLocus.CENTRALIZED_SWITCH;
            List<DispatchArm> arms = node instanceof CtAbstractSwitch<?> sw
                    ? armsOf(sw, hierarchy)
                    : chainArms((CtIf) node, hierarchy, root.getQualifiedName());
            out.add(new DispatchSite(locus, p.host(), node, arms));
        }
        return out;
    }

    /**
     * A chain's arms, read through the single {@link DispatchCommitDetector#chainOf}
     * decomposition — one arm per tested subtype, plus the residual {@code else}.
     *
     * <p>The type test is consumed into {@code fromSimpleName} and is deliberately
     * NOT recorded as a guard (F17): a type test SELECTS the state, and recording
     * it as a condition both loses the source state and decorates the edge with
     * what is really its own arm label.
     */
    private static List<DispatchArm> chainArms(CtIf head, Set<String> hierarchy, String rootQn) {
        List<DispatchArm> arms = new ArrayList<>();
        DispatchCommitDetector.TypeChain chain =
                DispatchCommitDetector.chainOf(head, hierarchy, rootQn);
        if (chain == null) return arms;
        for (DispatchCommitDetector.ChainLink link : chain.links()) {
            CtTypeReference<?> t = link.type();
            arms.add(new DispatchArm(t == null ? null : t.getSimpleName(), link.branch(),
                    null, null));
        }
        CtStatement otherwise = chain.otherwise();
        if (otherwise != null) {
            // The residual arm: reached in exactly the states no link claimed. Its
            // from-state is a SET, not a name, so it is left unattributed here and
            // computed by the walker, which is the only thing holding the residual.
            arms.add(new DispatchArm(null, otherwise, null, null));
        }
        return arms;
    }

    // ---- FUNCTIONAL_CALLABLE --------------------------------------------------

    /**
     * A transition function expressed as a lambda or an anonymous-class SAM
     * override. Same dispatch as a named centralized function, hosted differently,
     * which is why it maps onto {@code POLYMORPHIC} rather than getting an
     * encoding of its own — see {@link DispatchLocus#encoding()}.
     */
    public static List<DispatchSite> functionalSites(CtType<?> root, CtModel model) {
        List<DispatchSite> out = new ArrayList<>();
        for (CtElement callable :
                StateMachineClassifier.findFunctionalTransitionCallables(root, model)) {
            DispatchArm arm = new DispatchArm(null, callable, null, null);
            out.add(new DispatchSite(DispatchLocus.FUNCTIONAL_CALLABLE, callable, callable,
                    List.of(arm)));
        }
        return out;
    }

    // ---- shared locus tests ---------------------------------------------------

    private static boolean dispatchesOnHierarchy(CtAbstractSwitch<?> sw, Set<String> hierarchy) {
        try {
            CtExpression<?> selector = sw.getSelector();
            CtTypeReference<?> t = selector == null ? null : selector.getType();
            return t != null && hierarchy.contains(t.getQualifiedName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean insideFunctionalCallable(CtElement e) {
        try {
            if (e.getParent(CtLambda.class) != null) return true;
            CtType<?> owner = e.getParent(CtType.class);
            return owner instanceof CtClass<?> c && c.isAnonymous();
        } catch (Throwable t) {
            return false;
        }
    }

    private static CtMethod<?> enclosingMethod(CtElement e) {
        try {
            return e.getParent(CtMethod.class);
        } catch (Throwable t) {
            return null;
        }
    }
}
