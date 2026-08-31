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
import java.util.function.Consumer;
import java.util.stream.Collectors;

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

    /**
     * Why a hierarchy was <em>not</em> accepted. The two rejections are not
     * interchangeable, and the difference decides whether a rejected root's
     * nested sealed hierarchies get a look of their own (see
     * {@link io.sealfsm.Analyzer}).
     *
     * <ul>
     *   <li>{@link #VETOED} is a positive verdict about the <em>data type</em>:
     *       its members are composed into one another, which the carrier
     *       detector treats as a property of the family rather than of one
     *       method. Its members inherit that verdict.</li>
     *   <li>{@link #ABSTAINED} is not a verdict at all — the recognizers found
     *       nothing <em>here</em>, which says nothing about a machine declared
     *       inside. This is the reading the reason text already states
     *       ("may be event/&Sigma; type or unresolved dispatch").</li>
     * </ul>
     */
    public enum Rejection {
        /** Not a rejection: the hierarchy was accepted as a state machine. */
        NONE,
        /** No transition producer was found — an abstention, not a verdict. */
        ABSTAINED,
        /** The compositional veto: a verdict about the data type itself. */
        VETOED
    }

    public record Classification(boolean isStateMachine, Encoding encoding, String reason,
                                 Rejection rejection) {
        /** Rejection by abstention: nothing was recognised, and nothing is claimed. */
        public static Classification no(String reason) {
            return new Classification(false, Encoding.MIXED, reason, Rejection.ABSTAINED);
        }
        /** Rejection by verdict: a statement about the data type, binding on its members. */
        public static Classification veto(String reason) {
            return new Classification(false, Encoding.MIXED, reason, Rejection.VETOED);
        }
        public static Classification yes(Encoding enc, String reason) {
            return new Classification(true, enc, reason, Rejection.NONE);
        }
    }

    public Classification classify(CtType<?> root, CtModel model) {
        return classify(root, model, line -> { });
    }

    /**
     * The same decision, with every predicate it evaluates reported to
     * {@code trace} as it is evaluated ({@code --explain}).
     *
     * <p>A side channel on the real decision procedure, deliberately, rather than
     * a second procedure that re-derives the same answers for display. This
     * codebase refuses two notions of one thing everywhere else (one
     * {@code chainOf}, one commit predicate, one naming) for the standing reason:
     * the copy drifts, and a report that disagrees with the verdict it explains
     * is worse than no report. Every line here is emitted from the branch that
     * actually ran, so the trace cannot say a predicate passed where the
     * classification says it did not.
     */
    public Classification classify(CtType<?> root, CtModel model, Consumer<String> trace) {
        if (hasMarkerAnnotation(root)) {
            trace.accept("marker annotation (@Fsm/@FSM/@StateMachine): PRESENT "
                    + "— decisive, accepted");
            return Classification.yes(detectEncoding(root, model),
                    "explicit @Fsm/@StateMachine marker");
        }
        trace.accept("marker annotation (@Fsm/@FSM/@StateMachine): absent "
                + "— continuing structurally");

        // Precision gate, ahead of every structural signal: a hierarchy whose
        // members are composed into one another is a recursive data type, and a
        // recursive data type's "next" is a child, not a successor state. This
        // sinks tree builders, exhaustive folds and tag-dispatched deserializers
        // regardless of which recognizer would otherwise have claimed them — a
        // record component of the hierarchy type, for instance, gives such a type
        // an accessor that looks exactly like a per-state transition method.
        List<CarrierTransitionDetector.NestedProduction> nested =
                CarrierTransitionDetector.nestedProductions(root);
        if (CarrierTransitionDetector.composesItself(root)) {
            trace.accept("compositional veto: FIRED — " + describeNesting(nested));
            return Classification.veto(
                    "hierarchy members are composed into one another (a hierarchy value is a "
                            + "construction argument of another) — a recursive data type, not a state machine");
        }
        trace.accept("compositional veto: not fired — " + describeNesting(nested));

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

        trace.accept("per-state transition method (declared on a member, returns the hierarchy "
                + "type): " + verdict(distributed.size()) + describeMethods(distributed));
        trace.accept("centralized transition function (produces a hierarchy value AND "
                + "discriminates one): " + verdict(centralized.size())
                + describeMethods(centralized));
        trace.accept("functional transition callable (lambda / anonymous-class method with that "
                + "signature): " + verdict(functional.size()));
        trace.accept("dispatch with a hierarchy-typed commit (a switch or instanceof chain over "
                + "the hierarchy whose result is installed as a hierarchy value): "
                + verdict(producers.size()) + describeProducers(producers));

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
        List<CtMethod<?>> carriers = CarrierTransitionDetector.findCarrierTransitionMethods(root);
        if (CarrierTransitionDetector.qualifies(root)) {
            trace.accept("carrier-based per-state transition (successor handed to a "
                    + "non-hierarchy wrapper): " + verdict(carriers.size())
                    + describeMethods(carriers));
            return Classification.yes(Encoding.POLYMORPHIC, carrierReason(root));
        }
        trace.accept("carrier-based per-state transition (successor handed to a non-hierarchy "
                + "wrapper): FAILED — " + describeCarrierGap(carriers));
        trace.accept("no predicate above produced a transition producer: ABSTAINING. Not a "
                + "verdict about the hierarchy — it may be the event alphabet, or its dispatch "
                + "may sit somewhere no recognizer can see");
        // Abstention, stated as abstention. The old wording ("looks like a plain
        // sum type") asserted a positive classification the analysis had not made:
        // a sealed type reaches this line just as readily by being the event
        // alphabet Σ, or by being dispatched somewhere the recognizers cannot see.
        return Classification.no(
                "no transition producer found (may be event/Σ type or unresolved dispatch)");
    }

    // ---- --explain rendering --------------------------------------------------
    // Text only. Nothing below is consulted by a decision: each helper describes
    // evidence a predicate above has already weighed, and none is called from
    // anywhere but a trace line.

    private static String verdict(int found) {
        return found == 0 ? "FAILED — none found" : "passed — " + found + " found";
    }

    private static String describeMethods(List<CtMethod<?>> methods) {
        if (methods.isEmpty()) return "";
        String shown = methods.stream().limit(6)
                .map(m -> (m.getDeclaringType() == null ? "?" : m.getDeclaringType().getSimpleName())
                        + "#" + m.getSignature())
                .collect(Collectors.joining(", "));
        return " [" + shown
                + (methods.size() > 6 ? ", … " + (methods.size() - 6) + " more]" : "]");
    }

    private static String describeProducers(List<DispatchCommitDetector.Producer> producers) {
        if (producers.isEmpty()) return "";
        Set<String> commits = new LinkedHashSet<>();
        for (DispatchCommitDetector.Producer p : producers) commits.add(p.commit().name());
        return " committing via " + String.join("/", commits);
    }

    private static String describeNesting(List<CarrierTransitionDetector.NestedProduction> nested) {
        if (nested.isEmpty()) return "no production nests a hierarchy value inside another";
        Set<String> members = new LinkedHashSet<>();
        boolean self = false;
        for (CarrierTransitionDetector.NestedProduction n : nested) {
            members.add(n.member());
            self |= n.selfComposing();
        }
        return nested.size() + " nested production(s) across " + members.size() + " member(s)"
                + (self
                        ? ", at least one SELF-COMPOSING (a part of the current state is rebuilt "
                                + "into it), which is structural recursion and sufficient alone"
                        : ", none self-composing; the bound is one self-composing production, or "
                                + "≥2 nested productions across ≥2 distinct members");
    }

    /**
     * Which of the carrier predicate's conjuncts failed. It is one boolean to the
     * classifier and three questions to a reader, and "carrier methods on exactly
     * one member" is the difference between "not this idiom at all" and "one
     * member short of it".
     */
    private static String describeCarrierGap(List<CtMethod<?>> carriers) {
        if (carriers.isEmpty()) {
            return "no per-state method returns a wrapper carrying a hierarchy value";
        }
        Set<String> declaring = new LinkedHashSet<>();
        for (CtMethod<?> m : carriers) {
            if (m.getDeclaringType() != null) declaring.add(m.getDeclaringType().getSimpleName());
        }
        if (declaring.size() < 2) {
            return carriers.size() + " carrier method(s), but on only " + declaring.size()
                    + " permitted subtype " + declaring + " — a dispatch needs at least two";
        }
        return carriers.size() + " carrier method(s) on " + declaring + ", but none produces a "
                + "state OTHER than its own declaring type — every production is a self-loop, "
                + "which discriminates nothing";
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
     * Methods anywhere in the model, outside the hierarchy, that <em>produce</em> a
     * hierarchy value and <em>examine</em> one: the centralized transition
     * function, in either of the two ways the state reaches it.
     *
     * <p>The recognizer used to require a hierarchy-typed <b>parameter</b>, which
     * admits {@code T transition(T current, Event e)} and nothing else. That is one
     * idiom, not the notion. A stateful driver spells the same function
     *
     * <pre>{@code
     *   final class Driver {
     *       private H state = new Initial();
     *       H step(Event e) { ... discriminates this.state ... }   // H out, no H in
     *   }
     * }</pre>
     *
     * and it never matched: not distributed (not declared on the hierarchy), not
     * centralized (no H-typed parameter). Whether it was recovered at all then
     * depended on {@link DispatchCommitDetector} incidentally finding a commit at
     * the discrimination — so {@code return switch (state)} survived, while a
     * {@code switch} <em>statement</em> whose arms return, the ordinary pre-arrow
     * spelling of the same table, was lost outright along with its whole hierarchy.
     *
     * <p>The parameter is therefore no longer required. What replaces it is the
     * property the parameter was standing in for — that the method takes the state
     * as an input — asked directly:
     * {@link DispatchCommitDetector#discriminatesState}. The two disjuncts are the
     * two ways a method can be handed the state: as an argument, or by reading a
     * field it then discriminates.
     *
     * <p><b>Why the return type is not enough on its own.</b> "Returns H" is the
     * commit test for a value-returning host, and it is already applied here — but
     * for this recognizer the commit test alone degenerates into the whole
     * predicate, and a codomain says nothing about whether the state was consulted.
     * Dropping the parameter requirement with nothing in its place admits every
     * factory ({@code static Bolt factoryOpen() { return new Open(); }}) and every
     * accessor ({@code Relay state() { return state; }}) in the model, each of which
     * the extractor would then walk as a standalone dispatch and report as an edge
     * out of an undetermined source. Requiring the discrimination is what keeps the
     * widening to methods that are transition functions.
     */
    public static List<CtMethod<?>> findCentralizedTransitionMethods(CtType<?> root, CtModel model) {
        Set<String> hierarchy = hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();
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
            if (declaredInsideHierarchy) continue;
            if (takesHierarchyParameter(method, hierarchy)
                    || DispatchCommitDetector.discriminatesState(method, hierarchy, rootQn)) {
                out.add(method);
            }
        }
        return out;
    }

    /**
     * Is the state an <em>argument</em> of this method? Beyond recognition this is
     * a statement about scope, and the extractor reads it as one: a method handed
     * the state computes a successor from it end to end, so its whole body belongs
     * to the transition relation. A host that only returns H reads the state from a
     * field it also writes and logs and null-checks, so only its discrimination
     * does — which is why the two are walked differently.
     */
    public static boolean takesHierarchyParameter(CtMethod<?> method, Set<String> hierarchy) {
        return method.getParameters().stream()
                .map(CtParameter::getType)
                .anyMatch(p -> p != null && hierarchy.contains(p.getQualifiedName()));
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

    /**
     * Root plus all (transitively) permitted subtypes, as resolved CtTypes.
     *
     * <p>Resolved only, and necessarily so: a member whose declaration was never
     * read has no body to walk, no methods to recognise and no {@code permits}
     * clause to descend into. Membership is the wider question, and it does not
     * require readability — see {@link #hierarchyQualifiedNames}.
     */
    public static List<CtType<?>> hierarchyTypes(CtType<?> root) {
        List<CtType<?>> out = new ArrayList<>();
        collectHierarchy(root, out, new LinkedHashSet<>());
        return out;
    }

    /**
     * Every qualified name that names a member of this hierarchy, including the
     * members whose <em>declarations</em> did not resolve.
     *
     * <p><b>The gap this closes.</b> Under {@code noClasspath} a member Spoon
     * cannot bind still reaches the analysis as a reference carrying a guessed
     * qualified name. Built from resolved declarations alone, this set answered
     * "not in the hierarchy" for such a name — the identical answer a genuinely
     * foreign type gives — so {@link io.sealfsm.extract.StateExtractor}
     * enumerated the state from the {@code permits} clause while every recognizer
     * read it as foreign, and every edge mentioning it was lost. The two halves of
     * the tool disagreed about what the hierarchy contains. Withholding one state
     * file from {@code examples/traffic} took it from 3/3 resolved transitions to
     * 1/2; withholding {@code examples/door}'s three state files gave 0/5 plus six
     * nondeterminism warnings about a state called {@code <unknown>}.
     *
     * <p><b>Why a permits reference may be admitted where a resemblance may
     * not.</b> What is added here is a name the {@code permits} clause itself
     * wrote. {@code permits X} is the compiler-checked statement that X is a
     * member — the same statement the exact state enumeration already rests on,
     * which is why the state appears in the output at all. Only the
     * <em>spelling</em> Spoon assigns to the reference is a guess; that the type
     * it denotes belongs to the hierarchy is not. So no simple name is matched
     * here and no resemblance is consulted (contrast
     * {@link TypeResolutionAudit#resembling}, which only ever selects the text of
     * a diagnostic): a use site joins the hierarchy only when Spoon spelled it
     * with the identical qualified name, which is the same equality test every
     * resolved member already goes through.
     *
     * <p><b>The residual risk, stated rather than argued away.</b> Spoon derives
     * both guesses from the same package-and-import context, so they normally
     * agree or both fail. But if the guess is WRONG — an unreadable import makes
     * {@code p.Yellow} the guess for a type that is really {@code q.Yellow} — a
     * foreign type spelled the same way in the same context is admitted as a
     * state and an edge to it is published RESOLVED, which is a fabrication and
     * the failure mode the soundness invariant forbids outright. The guesses are
     * also not stable: one unresolvable type was observed reaching a single model
     * as both {@code pkg.Signal} and a bare {@code Signal}. Recovery is therefore
     * partial by construction — a use site Spoon spelled differently stays
     * unresolved exactly as before — and it is conditional on an input the tool
     * should not have been handed in the first place: a source set omitting part
     * of a hierarchy.
     *
     * <p><b>Recovering the incoming edges does not recover the outgoing ones.</b>
     * The declaration is still unread, so a producer declared INSIDE it was never
     * examined, and under a polymorphic encoding that is the state's entire
     * outbound relation. {@link io.sealfsm.Analyzer} records that as an explicit
     * unresolved transition out of each such state, so what this method buys is a
     * recovered edge and never a cleaner-looking score.
     */
    public static Set<String> hierarchyQualifiedNames(CtType<?> root) {
        Set<String> names = new LinkedHashSet<>();
        for (CtType<?> t : hierarchyTypes(root)) names.add(t.getQualifiedName());
        names.addAll(unresolvedMemberNames(root));
        return names;
    }

    /**
     * The members named by a {@code permits} clause the analysis could read but
     * whose own declarations it could not — the difference between
     * {@link #hierarchyQualifiedNames} and {@link #hierarchyTypes}.
     *
     * <p>Transitive over the members that DID resolve, because a composite state
     * is where the loss compounds: an unread member of a nested sealed type is
     * missing from the same membership set for the same reason. It cannot recurse
     * past an unread member, which is exactly the child-state loss the resolution
     * diagnostic reports separately.
     *
     * <p>Exposed rather than folded into the set above so that {@code Analyzer}
     * can ask which states these are without rebuilding the traversal, and so the
     * report and the recovery cannot drift into naming different sets.
     */
    public static Set<String> unresolvedMemberNames(CtType<?> root) {
        Set<String> out = new LinkedHashSet<>();
        for (CtType<?> t : hierarchyTypes(root)) {
            for (CtTypeReference<?> ref : SpoonCompat.unresolvedPermittedTypes(t)) {
                String qn = SpoonCompat.resolutionName(ref);
                if (qn != null && !qn.isEmpty()) out.add(qn);
            }
        }
        return out;
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
