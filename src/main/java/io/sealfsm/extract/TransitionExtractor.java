package io.sealfsm.extract;

import io.sealfsm.detect.CarrierTransitionDetector;
import io.sealfsm.detect.DispatchCommitDetector;
import io.sealfsm.detect.dispatch.CasePatterns;
import io.sealfsm.detect.dispatch.CommitClassifier;
import io.sealfsm.detect.dispatch.DispatchFinder;
import io.sealfsm.detect.dispatch.DispatchLocus;
import io.sealfsm.detect.dispatch.DispatchSite;
import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.model.CommitForm;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.StateNaming;
import io.sealfsm.model.Transition;
import spoon.reflect.CtModel;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtBreak;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtCatch;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtContinue;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtTry;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtTypePattern;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtShadowable;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recovers transitions for a classified state machine. Two encodings are
 * supported:
 *
 * <p><b>Distributed</b> (classic State pattern): each state class declares
 * transition method(s) returning the hierarchy type. The <em>from</em>-state is
 * the declaring class; the <em>to</em>-state(s) come from resolving the
 * method's produced expressions.
 *
 * <p><b>Centralized</b>: a single function switches over the current state
 * (type patterns) and returns the next. The <em>from</em>-state is the matched
 * pattern type; the <em>to</em>-state(s) come from the matching arm.
 *
 * <p>Both encodings are walked by a single recursive, guard-carrying traversal
 * ({@link #walk}). Rather than flat-scanning a method for {@code return}
 * statements, the walker descends the control-flow structure of the body so
 * that:
 * <ul>
 *   <li>the condition of an enclosing {@code if} becomes the transition guard
 *       (and its negation guards the {@code else}/fall-through branch);</li>
 *   <li>values produced <em>inside</em> an {@code if} — a common shape in real
 *       code, e.g. {@code case Locked l -> { if (e instanceof Coin) yield
 *       new Unlocked(); yield l; }} — are recovered rather than dropped;</li>
 *   <li>a switch nested inside an arm (switch-over-event within a
 *       switch-over-state) is descended into.</li>
 * </ul>
 *
 * <p>Switch / pattern-matching handling is the most Spoon-version-sensitive area
 * in the project, so every such access is guarded; anything unrecognised is
 * recorded as an unresolved transition (or a diagnostic) instead of throwing.
 */
public final class TransitionExtractor {

    private final TransitionResolver resolver;
    private final Set<String> hierarchyQualifiedNames;
    private final String rootQualifiedName;
    private final List<String> diagnostics = new ArrayList<>();

    // F7: functional-callable walk context. The selector is the root-typed value
    // the current callable dispatches on (a parameter, threaded so `instanceof`
    // tests and `return <selector>` resolve against it); the concrete-state names
    // seed the entry from-set (every permitted subtype the selector could be).
    // Both are set only while a functional callable is walked.
    private CtVariable<?> selector = null;
    private Set<String> concreteStateSimpleNames = Set.of();

    // F22: whether a transition method's NAME carries an input symbol, decided per
    // hierarchy from whether the names DISCRIMINATE (see eventName). Two flags,
    // because the two walks draw their names from different sets: the per-state
    // methods of the hierarchy, and the methods that supply F7 functional callables.
    private boolean distributedNamesDiscriminate = false;
    private boolean functionalNamesDiscriminate = false;

    // F2: mutation-encoding context, populated only while the GoF/mutation
    // fallback runs (a hierarchy with no return-based transition method). Left
    // empty for distributed/centralized extraction, so those paths are unchanged.
    /**
     * The site the walker is currently inside, or {@code null} between sites.
     *
     * <p>This replaces the two hand-set booleans {@code carrierMode} and
     * {@code mutationMode}. They were <em>modes on the walker</em>: a caller set
     * one, walked, and cleared it in a {@code finally}, and every predicate that
     * consulted them was really asking "which recognizer am I running for?".
     * That question has an answer now — the site — and asking the site instead
     * means the two can never be set for a body they do not describe, or left set
     * across one.
     */
    private WalkSite walkSite;

    /**
     * What the walker is inside: which discovery route produced the site, where
     * its dispatch lives, and how it installs a successor.
     *
     * <p>{@code route} is not redundant with {@code locus}. The F2 mutation
     * fallback has no locus at all — it is not a discrimination but a scan of the
     * methods that write the state field — and it is precisely the route, not the
     * commit form, that licenses reading a state-field write as a produced
     * successor. A {@code FIELD_MUTATION} commit found at a real dispatch is
     * already claimed by that dispatch's own walk.
     */
    private record WalkSite(Route route, DispatchLocus locus, CommitForm commit) { }

    /** How a walked body was discovered. */
    private enum Route { OVERRIDE, CARRIER, CENTRALIZED_METHOD, COMMIT_DISPATCH, FUNCTIONAL,
                         MUTATION_FALLBACK }

    /**
     * Does the site being walked install its successor through a <em>carrier</em>?
     * The old {@code carrierMode}, asked of the commit rather than of the caller —
     * which is what makes it true at every locus instead of only at
     * {@code POLYMORPHIC_OVERRIDE}.
     *
     * <p>Both carrier commits answer yes, and the scope line they share is the
     * reason they must: a successor COMPUTED inside a helper stays unresolved
     * rather than being chased, whether the wrapper was returned by a per-state
     * override or by a centralized switch. Reading that bound off the route would
     * have made it an accident of which recognizer ran.
     */
    private boolean commitsThroughCarrier() {
        return walkSite != null && (walkSite.commit() == CommitForm.POLY_CARRIER
                || walkSite.commit() == CommitForm.CARRIER_RETURN);
    }

    /** Is the walker inside the F2 mutation fallback? The old {@code mutationMode}. */
    private boolean inMutationFallback() {
        return walkSite != null && walkSite.route() == Route.MUTATION_FALLBACK;
    }
    private Set<String> stateFieldNames = Set.of();
    // F22: the recognised state mutators, keyed on `declaringType#signature` — the
    // same key hosts are deduped on elsewhere, so the recognizer and the call
    // matcher cannot drift into two notions of "mutator". `mutatorNames` holds the
    // simple names of exactly those methods and is a PREFILTER only: it keeps the
    // declaration lookup off every unrelated call, and never admits one on its own.
    private Set<String> mutatorKeys = Set.of();
    private Set<String> mutatorNames = Set.of();
    // F22: calls whose callee declaration Spoon could not bind, so "is this a
    // mutator?" was answered from the call's SHAPE plus the name prefilter rather
    // than from the declaration. Reported, so a name match never reads as a proof.
    private final Set<String> unboundMutatorCalls = new LinkedHashSet<>();
    // F22: state-field writes admitted on the field's NAME because its declared
    // type could not be resolved. Answered in the direction that cannot drop an
    // edge, and reported, so the last remaining name match is not silent either.
    private final Set<String> unboundStateFieldWrites = new LinkedHashSet<>();
    // F22: methods shaped like a mutator — one hierarchy-typed parameter, a write
    // to a hierarchy-typed field — whose commit does not come FROM that parameter,
    // so a call's argument says nothing about where the machine goes. Their call
    // sites contribute no edge; the commit each performs is recovered from its own
    // body instead, without a source. A recall gap, so it is reported.
    private final Set<String> unattributedMutators = new LinkedHashSet<>();

    // The commit form of the dispatch currently being walked, or null outside one.
    // Only a chain consults it: a switch commits its whole result in one place the
    // detector already identified, whereas a chain commits inside each branch, so
    // the walker has to know that an assignment there IS the produced successor
    // rather than the incidental local write it is everywhere else.
    private CommitForm dispatchCommit = null;

    // F3: bounded inter-procedural resolution. k = 2 keeps the fixed analysis
    // terminating; the stack also detects recursion cycles.
    private static final int MAX_INTERPROC_DEPTH = 2;
    private final Deque<String> interProcStack = new ArrayDeque<>();
    private int interProcResolvedEdges = 0;

    // F9: calls to an in-model helper that provably cannot return normally. The
    // arms they occupy carry no transition, so no edge is emitted; the count is
    // reported as a diagnostic rather than letting them vanish unremarked.
    private int nonReturningCalls = 0;

    // F20: produced values that nest a hierarchy value inside another one. The
    // hierarchy survived the (now bounded) compositional veto, so this expression
    // is a local observation, not a verdict about the type — and a composed node
    // is not a successor, so its target is not claimed. Recorded as an unresolved
    // edge and counted, which is what keeps "bounded veto" from meaning "the
    // remaining nesting is silently reported as a transition".
    private int nestedProductions = 0;

    // F18: the `return` statements the inter-procedural fold currently in progress
    // has ACCOUNTED FOR — either walked through to a producer, or proven unable to
    // execute in the caller's context. Non-null only while a callee body is being
    // summarised, and identity-keyed on purpose: Spoon gives CtElement deep
    // structural equality, so two arms both spelling `return new Armed();` compare
    // equal and one would silently stand in for the other.
    private Set<CtReturn<?>> accountedReturns = null;

    // F18: returns of an in-model helper that the walk could neither reach nor
    // prove dead. Each one is emitted as an unresolved transition; the count is
    // reported so a partly-read body reads as the recall gap it is.
    private int unreadableReturns = 0;

    // F13: reads whose "is this local reassigned?" answer could not be decided on
    // variable identity because Spoon could not bind a same-named write to any
    // declaration, and so rests on the name alone. Rare, and reported: the answer
    // is taken in the direction that cannot fabricate an edge, but it is a name
    // match standing in for a proof and should not read like one.
    private final Set<String> nameOnlyReassignmentChecks = new LinkedHashSet<>();

    // F4: the closed-world event alphabet Σ enumerated from the sealed/enum event
    // parameter of centralized transition functions, plus the qualified names of
    // that event type (root + members) so a switch-over-event can be recognised
    // and its arms attributed to the matched event.
    private final Set<String> alphabet = new LinkedHashSet<>();
    private final Set<String> eventQualifiedNames = new LinkedHashSet<>();
    // Simple names of the event parameters of the method currently being walked,
    // so `event == UserCall.CLOSE` / `event instanceof SegmentArrival` in a guard
    // can be attributed to the event that triggers the edge (F8).
    private Set<String> eventParamNames = new LinkedHashSet<>();
    // "<ownerQualifiedName>#<CONSTANT>" -> the Σ symbol that constant contributes,
    // recorded while Σ is enumerated so edge labels and Σ cannot drift apart.
    private final Map<String, String> eventSymbolByConstant = new LinkedHashMap<>();
    // Set while walking the arm of a switch-over-event whose label DECONSTRUCTS the
    // event (`case Send(Signal s) ->`). An inner switch on that binding refines the
    // label from the event family to the exact input, `Send` -> `Send.HEADERS`.
    private ComponentEvent componentEvent = null;

    /**
     * An event arm that bound one of the event's components, so a nested switch on
     * that binding can be recognised and its arms composed back into full Σ symbols.
     *
     * @param prefix             the event the outer arm matched, e.g. {@code Send}
     * @param boundName          the name the component was bound to, e.g. {@code s}
     * @param enumQualifiedName  the component's enum type, checked so an unrelated
     *                           switch that happens to reuse the name is not claimed
     */
    private record ComponentEvent(String prefix, String boundName, String enumQualifiedName) {
    }

    // F8: polymorphic-carrier walk context. While set, the walker descends one
    // level into carrier call arguments and inter-procedural folding is disabled —
    // the carrier encoding is analysed strictly intra-procedurally, so a successor
    // computed by a helper is recorded unresolved rather than chased.


    // True while the walk is inside an else / default / fall-through path. An edge
    // produced there with no event label is the state's default ("otherwise")
    // transition — a real, fully-specified edge, recorded as such rather than as a
    // nameless leftover. Saved and restored around each descent, which is exact for
    // a single-threaded recursive walk.
    private boolean otherwisePath = false;

    // The commit mechanisms actually exercised while extracting this machine, and
    // the states a dispatch arm (or a per-state transition method) selected. The
    // latter is what makes "terminal" a claim about a state the analysis SAW —
    // a state no arm matched has no outbound edges merely because none were
    // recovered, and calling that terminal would dress a recall gap as a result.
    private final Set<CommitForm> commitForms = new LinkedHashSet<>();
    private final Set<String> dispatchedStates = new LinkedHashSet<>();
    private final StateNaming naming;

    public TransitionExtractor(Set<String> hierarchyQualifiedNames, String rootQualifiedName) {
        this(hierarchyQualifiedNames, rootQualifiedName, StateNaming.EMPTY);
    }

    /**
     * @param naming the id assignment produced by {@link StateExtractor} for this
     *               hierarchy. Every state name this class emits — a from-state, a
     *               dispatched state, a resolved target — goes through it, so an
     *               endpoint and the state it refers to cannot be spelled
     *               differently when two permitted subtypes share a simple name.
     */
    public TransitionExtractor(Set<String> hierarchyQualifiedNames, String rootQualifiedName,
                               StateNaming naming) {
        this.hierarchyQualifiedNames = hierarchyQualifiedNames;
        this.rootQualifiedName = rootQualifiedName;
        this.naming = naming == null ? StateNaming.EMPTY : naming;
        this.resolver = new TransitionResolver(hierarchyQualifiedNames, rootQualifiedName, this.naming);
    }

    /** The id of the state declared by {@code type} — never its bare simple name. */
    private String stateId(CtType<?> type) {
        return naming.idFor(type.getQualifiedName());
    }

    /** The id of the state named by {@code ref} — never its bare simple name. */
    private String stateId(CtTypeReference<?> ref) {
        return naming.idFor(ref.getQualifiedName());
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    /** The event alphabet Σ recovered from sealed/enum event parameters (F4). */
    public Set<String> alphabet() {
        return alphabet;
    }

    /** How this machine's dispatch committed its successors — the third axis. */
    public Set<CommitForm> commitForms() {
        return commitForms;
    }

    /**
     * The states a dispatch arm (or a per-state transition method) selected. A
     * state in this set with no outbound edge is genuinely absorbing; a state
     * outside it simply was not reached by the analysis.
     */
    public Set<String> dispatchedStates() {
        return dispatchedStates;
    }

    public List<Transition> extract(CtType<?> root, CtModel model) {
        Set<Transition> out = new LinkedHashSet<>();

        // ONE recognition call. Every body this method walks comes from a
        // DispatchSite, and the site says which locus produced it; the extractor no
        // longer re-runs four recognizers of its own and no longer has to keep its
        // notion of "a transition method" in step with the classifier's.
        DispatchFinder.Sites sites = DispatchFinder.find(root, model);
        List<CtMethod<?>> distributed = methodHosts(sites.overrides());
        List<CtMethod<?>> centralized = methodHosts(sites.centralized());
        // F7: transition callables expressed as lambdas / anonymous-class methods.
        List<CtElement> functional = new ArrayList<>();
        for (DispatchSite site : sites.functional()) functional.add(site.host());

        // The concrete states the selector can be, used to seed the functional
        // walk's entry from-set (finding F7).
        this.concreteStateSimpleNames = new LinkedHashSet<>();
        for (CtType<?> t : StateMachineClassifier.hierarchyTypes(root)) {
            if (!t.getQualifiedName().equals(rootQualifiedName)) {
                concreteStateSimpleNames.add(stateId(t));
            }
        }

        // F3: a transition function that is *invoked* by another transition
        // function is an inter-procedural helper — its return values are folded
        // into the caller at the call site, so extracting it standalone would
        // double-count and emit spurious undetermined-origin edges. Exclude those.
        Set<String> helperSignatures = interproceduralHelperSignatures(distributed, centralized);

        // F22: a transition method's NAME is an input symbol only when it
        // DISCRIMINATES — see eventName. Decided here, once per hierarchy and
        // before any body is walked, over the methods each walk will actually own.
        Set<String> distributedNames = new LinkedHashSet<>();
        for (CtMethod<?> m : distributed) {
            if (!helperSignatures.contains(m.getSignature())) distributedNames.add(m.getSimpleName());
        }
        this.distributedNamesDiscriminate = distributedNames.size() > 1;
        Set<String> functionalNames = new LinkedHashSet<>();
        for (CtElement callable : functional) {
            String enclosing = enclosingMethodName(callable);
            if (enclosing != null) functionalNames.add(enclosing);
        }
        this.functionalNamesDiscriminate = functionalNames.size() > 1;

        for (DispatchSite site : sites.overrides()) {
            CtMethod<?> m = (CtMethod<?>) site.host();
            if (!helperSignatures.contains(m.getSignature())) {
                walkAt(site, Route.OVERRIDE, CommitForm.VALUE_RETURN, () -> extractDistributed(m, out));
            }
        }
        List<DispatchCommitDetector.Producer> producers = DispatchCommitDetector.find(root, model);
        Set<String> producerHosts = new HashSet<>();
        for (DispatchCommitDetector.Producer p : producers) producerHosts.add(methodKey(p.host()));

        Set<String> walkedMethods = new HashSet<>();
        for (DispatchSite site : sites.centralized()) {
            CtMethod<?> m = (CtMethod<?>) site.host();
            if (helperSignatures.contains(m.getSignature())) continue;
            // Which walk owns this host is decided by whether the state is an
            // ARGUMENT of it. A method handed the state computes a successor from it
            // end to end, so its whole body is the transition relation — that is what
            // makes ShutterLogic's `return current;` AFTER its chain a real self-loop
            // on the states no link claimed, rather than a stray statement.
            //
            // A host that only RETURNS the hierarchy type read the state out of a
            // field, and its body is a driver method containing a dispatch: the
            // `return this.state;` below the switch is a read-back of what the commit
            // already installed, and walking it would emit a successor with no
            // attributable source. Where such a host has a recognised producer, that
            // producer walks exactly the discrimination and knows the real commit
            // form, so it owns the body; walking it here as well would relabel a
            // FIELD_MUTATION machine VALUE_RETURN on the commit axis.
            if (producerHosts.contains(methodKey(m))
                    && !StateMachineClassifier.takesHierarchyParameter(m, hierarchyQualifiedNames)) {
                continue;
            }
            walkAt(site, Route.CENTRALIZED_METHOD, CommitForm.VALUE_RETURN,
                    () -> extractCentralized(m, out));
            walkedMethods.add(methodKey(m));
        }
        for (DispatchSite site : sites.functional()) {
            walkAt(site, Route.FUNCTIONAL, CommitForm.VALUE_RETURN,
                    () -> extractFunctional(site.host(), out));
        }
        // The widened centralized recognizer: a switch over the hierarchy whose
        // result is committed as a hierarchy value, wherever it is hosted. Hosts
        // the signature-based recognizer already walked are skipped so one body is
        // never walked twice (harmless for the edge set, which is a Set, but it
        // would double-count the inter-procedural fold statistic).
        List<DispatchSite> producerSites = sites.producers();
        for (int i = 0; i < producers.size(); i++) {
            DispatchCommitDetector.Producer p = producers.get(i);
            if (walkedMethods.contains(methodKey(p.host()))
                    || helperSignatures.contains(p.host().getSignature())) {
                commitForms.add(p.commit());
                continue;
            }
            // The site and the producer are the two halves of one dispatch: the
            // site is the LOCUS, the producer carries the COMMIT the classifier
            // recognised. They are built from the same scan in the same order, so
            // index i pairs them; the site is what the walker is told it is inside.
            DispatchSite site = i < producerSites.size() ? producerSites.get(i) : null;
            walkAt(site, Route.COMMIT_DISPATCH, p.commit(), () -> extractCommitDispatch(p, out));
        }
        // F8: per-state methods that return a *carrier* wrapping the successor.
        // Run whenever such a method exists, not only when the lists above are
        // empty: a hierarchy that also happens to expose one method returning the
        // hierarchy type (a helper, a record accessor) would otherwise classify as
        // plain distributed and silently lose every carrier edge. Methods that
        // return the hierarchy type directly are excluded here — the distributed
        // walker above already owns those — so the two paths never share a body.
        for (DispatchSite site : sites.carriers()) {
            CtMethod<?> m = (CtMethod<?>) site.host();
            walkAt(site, Route.CARRIER, CommitForm.POLY_CARRIER, () -> extractCarrier(m, out));
        }

        if (distributed.isEmpty() && centralized.isEmpty() && functional.isEmpty() && out.isEmpty()) {
            // F2: GoF / field-mutation encoding. Run only as a fallback when no
            // return-based transition method exists, so a hierarchy that already
            // exposes a functional transition is left untouched — a context that
            // merely stores a functional result is not re-mined as a transition here.
            extractMutationEncoding(root, model, out);
        }
        if (interProcResolvedEdges > 0) {
            diagnostics.add(interProcResolvedEdges + " transition target(s) resolved via bounded "
                    + "inter-procedural summaries (depth ≤ " + MAX_INTERPROC_DEPTH
                    + "); precision-sensitive — audit separately");
        }
        if (unreadableReturns > 0) {
            diagnostics.add(unreadableReturns + " return(s) of an in-model helper could not be "
                    + "reached by the walk (a construct outside its modelled subset encloses "
                    + "them); each is recorded as an unresolved transition, never dropped");
        }
        if (nonReturningCalls > 0) {
            diagnostics.add(nonReturningCalls + " call(s) to a helper that cannot return normally "
                    + "(always throws or diverges) contributed no transition — the arms holding "
                    + "them are undefined inputs, not unresolved targets");
        }
        if (nestedProductions > 0) {
            diagnostics.add(nestedProductions + " produced value(s) NEST a hierarchy value inside "
                    + "another (composition, not succession); each is recorded as an unresolved "
                    + "transition. Too few, and on too few members, to veto the hierarchy — "
                    + "review whether this is a recursive data type");
        }
        if (distributedNames.size() == 1) {
            diagnostics.add("every state spells its transition '" + distributedNames.iterator().next()
                    + "', so that one name names the transition FUNCTION and discriminates "
                    + "nothing; it is not emitted as an event symbol. Edges from these methods "
                    + "therefore carry no label unless Σ is recovered from the parameter, which "
                    + "for this encoding is future work (F22)");
        }
        if (!unattributedMutators.isEmpty()) {
            diagnostics.add("method(s) " + unattributedMutators + " take a hierarchy value and "
                    + "write the state field, but commit something OTHER than what they were "
                    + "handed, so a call site's argument is not its successor. Their calls "
                    + "contribute no edge; each such method's own commit is recovered from its "
                    + "body, with no source state to attribute it to (F22)");
        }
        if (!unboundStateFieldWrites.isEmpty()) {
            diagnostics.add("write(s) to field(s) " + unboundStateFieldWrites + " were taken as "
                    + "state commits on the field's NAME, because its declared type could not be "
                    + "resolved. Answered in the direction that cannot drop a commit, but a "
                    + "same-named field of another machine would be absorbed here (F22)");
        }
        if (!unboundMutatorCalls.isEmpty()) {
            diagnostics.add("call(s) to " + unboundMutatorCalls + " could not be bound to a "
                    + "declaration, so 'is this a state mutator?' was answered from the call's "
                    + "shape and its name rather than from the mutator's body — a name match "
                    + "standing in for a proof (F22)");
        }
        Set<String> nameOnly = new LinkedHashSet<>(nameOnlyReassignmentChecks);
        nameOnly.addAll(resolver.nameOnlyReassignmentChecks());
        if (!nameOnly.isEmpty()) {
            diagnostics.add("reassignment of local(s) " + nameOnly + " was decided by NAME, not by "
                    + "variable identity — a same-named write could not be bound to a declaration. "
                    + "Answered conservatively (treated as reassigned), which may cost resolution "
                    + "on an unrelated local of the same name");
        }
        return new ArrayList<>(out);
    }

    /**
     * Walk one site, with the walker told what it is inside for the duration.
     *
     * <p>This is where {@code carrierMode} and {@code mutationMode} used to be set
     * by hand at four different call sites. The difference is not stylistic: a
     * mode is a claim the caller makes about a body, and nothing checked that the
     * claim matched the body or that it was cleared afterwards. A site is the body,
     * so the two cannot come apart, and every predicate that used to ask "which
     * recognizer am I running for?" now asks the site the same question.
     */
    private void walkAt(DispatchSite site, Route route, CommitForm commit, Runnable walk) {
        WalkSite saved = this.walkSite;
        this.walkSite = new WalkSite(route, site == null ? null : site.locus(), commit);
        try {
            walk.run();
        } finally {
            this.walkSite = saved;
        }
    }

    /** The named-method hosts of a site list, in site order. */
    private static List<CtMethod<?>> methodHosts(List<DispatchSite> sites) {
        List<CtMethod<?>> out = new ArrayList<>();
        for (DispatchSite site : sites) {
            if (site.host() instanceof CtMethod<?> m) out.add(m);
        }
        return out;
    }

    /**
     * Signatures of transition methods invoked by another transition method:
     * inter-procedural helpers whose return values are folded into their callers
     * (F3), so they must not also be extracted as standalone machine fragments.
     */
    private Set<String> interproceduralHelperSignatures(List<CtMethod<?>> distributed,
                                                        List<CtMethod<?>> centralized) {
        Set<String> callable = new HashSet<>();
        for (CtMethod<?> m : distributed) callable.add(m.getSignature());
        for (CtMethod<?> m : centralized) callable.add(m.getSignature());

        Set<String> helpers = new HashSet<>();
        List<CtMethod<?>> all = new ArrayList<>(distributed);
        all.addAll(centralized);
        for (CtMethod<?> caller : all) {
            for (CtInvocation<?> inv : caller.getElements(new TypeFilter<>(CtInvocation.class))) {
                CtMethod<?> callee = calleeMethod(inv);
                if (callee != null && callable.contains(callee.getSignature())
                        && !callee.getSignature().equals(caller.getSignature())) {
                    helpers.add(callee.getSignature());
                }
            }
        }
        return helpers;
    }

    // ---- distributed (State pattern) -----------------------------------------

    private void extractDistributed(CtMethod<?> method, Set<Transition> out) {
        CtType<?> declaring = method.getDeclaringType();
        if (declaring == null) return;
        commitForms.add(CommitForm.VALUE_RETURN);
        // The declaring class IS the source state — exact, no data-flow needed —
        // so it counts as dispatched even when the body produces nothing.
        dispatchedStates.add(stateId(declaring));
        // The from-state is fixed to the declaring class; a switch inside the
        // body dispatches on the event, not on the state, so from is preserved.
        walk(method.getBody(), stateId(declaring), eventName(method, distributedNamesDiscriminate),
                null, out);
    }

    // ---- centralized (single transition function) ----------------------------

    /**
     * Walk a transition producer discovered by the <em>widened</em> centralized
     * recognizer: a switch over the hierarchy whose result is committed as a
     * hierarchy value, hosted anywhere and installed by return, by field write or
     * through a local accumulator.
     *
     * <p>Only the dispatch switch is walked, never the whole host body. That bound
     * matters: {@code handleEvent} in the field-mutation idiom ends with
     * {@code return this.currentState;}, and walking the body would read that
     * trailing return as a producer with no attributable source and emit a
     * fictitious unresolved edge. The switch is where the transition relation is;
     * everything around it is plumbing.
     */
    private void extractCommitDispatch(DispatchCommitDetector.Producer producer, Set<Transition> out) {
        commitForms.add(producer.commit());
        // Σ comes from the host's event parameter exactly as for a named
        // transition function, so a nested switch-over-event can label its arms.
        enumerateEventAlphabet(producer.host());
        if (producer.dispatch() instanceof CtAbstractSwitch<?> sw) {
            walkSwitch(sw, null, null, null, out);
        } else if (producer.dispatch() instanceof CtIf head) {
            // An instanceof chain. The selector is threaded as walk context so the
            // type tests attribute from-states instead of piling up as guards; at
            // entry the selector may be on any concrete state, which is the same
            // closed-world set the permits clause gives.
            CtVariable<?> savedSelector = this.selector;
            CommitForm savedCommit = this.dispatchCommit;
            this.selector = producer.selector();
            this.dispatchCommit = producer.commit();
            try {
                walkTypeChain(head, concreteStateSimpleNames, null, null, out);
            } finally {
                this.selector = savedSelector;
                this.dispatchCommit = savedCommit;
            }
        }
    }

    /** Declaring type + signature: unique across the model, unlike a bare signature. */
    private static String methodKey(CtMethod<?> m) {
        CtType<?> declaring = m.getDeclaringType();
        return (declaring == null ? "?" : declaring.getQualifiedName()) + "#" + m.getSignature();
    }

    private void extractCentralized(CtMethod<?> method, Set<Transition> out) {
        commitForms.add(CommitForm.VALUE_RETURN);
        // F4: recover the closed-world event alphabet Σ from the event parameter
        // (and register the event type so a nested switch-over-event can label
        // each arm). Done before the walk so walkSwitch sees eventQualifiedNames.
        enumerateEventAlphabet(method);
        // The hierarchy-typed parameter this function dispatches on. Threading it
        // as the walk's selector is what lets an `if (current instanceof Closed)`
        // body attribute from-states: the signature recognizer finds such a method
        // perfectly well, and before this its every type test was read as a data
        // guard, so a fully recognised machine still reported `<unknown> -> ?`
        // for each of its edges. A switch-dispatched body sets no type test on the
        // selector, so nothing changes for one.
        CtVariable<?> saved = this.selector;
        this.selector = selectorParameter(method.getParameters());
        try {
            if (!containsStateDispatch(method)) {
                // Nothing we could attribute from-states to. Walk anyway so
                // produced targets remain visible (recorded with an undetermined
                // origin), and flag for manual review.
                diagnostics.add("centralized method '" + method.getSignature()
                        + "' has no recognised switch or instanceof chain over the "
                        + "state type; from-states could not be attributed");
            }
            // from starts unknown and is set per matched state-pattern case or
            // type test; the event label is null until an arm names it (F4).
            walk(method.getBody(), null, null, null, out);
        } finally {
            this.selector = saved;
        }
    }

    /**
     * Enumerate Σ from a transition method's event parameter — the same
     * closed-world trick used for states (finding F4). The event parameter is a
     * parameter (other than the state selector) whose type is a sealed hierarchy
     * or an enum; Σ is then its permitted subtypes / enum constants, exact and
     * complete. Records both the symbols (into the alphabet) and the qualified
     * names of the event type and its members (so {@link #walkSwitch} can attribute
     * a switch-over-event's arms to the matched event).
     */
    private void enumerateEventAlphabet(CtMethod<?> method) {
        eventParamNames = new LinkedHashSet<>();
        for (CtParameter<?> p : method.getParameters()) {
            CtTypeReference<?> pt = p.getType();
            if (pt == null || hierarchyQualifiedNames.contains(pt.getQualifiedName())) {
                continue; // skip the state selector (and untyped params)
            }
            CtType<?> decl = pt.getTypeDeclaration();
            if (decl == null) continue;

            List<String> symbols = new ArrayList<>();
            if (decl instanceof CtEnum<?> en) {
                // The parameter is itself the enum, so a guard names the constant
                // bare (`tick == Tick.ARM` selects `ARM`).
                for (CtEnumValue<?> v : en.getEnumValues()) {
                    symbols.add(v.getSimpleName());
                    eventSymbolByConstant.put(constantKey(pt.getQualifiedName(), v.getSimpleName()),
                            v.getSimpleName());
                }
            } else if (SpoonCompat.isSealed(decl)) {
                for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(decl)) {
                    symbols.addAll(eventSymbolsFor(ref));
                }
            } else {
                continue; // not a closed event type — no exact Σ to recover
            }
            if (symbols.isEmpty()) continue;

            eventParamNames.add(p.getSimpleName());
            eventQualifiedNames.add(pt.getQualifiedName());
            for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(decl)) {
                eventQualifiedNames.add(ref.getQualifiedName());
            }
            alphabet.addAll(symbols);
        }
    }

    /**
     * The Σ symbols a permitted member of a sealed event type contributes. Usually
     * the member itself, but a member that is an {@code enum} — the common shape
     * for a family of related inputs, e.g. {@code enum UserCall implements Event}
     * — contributes one symbol per constant ({@code UserCall.CLOSE}), because a
     * guard tests the constant, not the enum. Σ stays exact: an enum's constants
     * are as closed as a {@code permits} clause.
     */
    private List<String> eventSymbolsFor(CtTypeReference<?> ref) {
        List<String> out = new ArrayList<>();
        CtType<?> member = ref.getTypeDeclaration();
        if (member instanceof CtEnum<?> en && !en.getEnumValues().isEmpty()) {
            for (CtEnumValue<?> v : en.getEnumValues()) {
                String symbol = composedSymbol(ref.getSimpleName(), v.getSimpleName());
                out.add(symbol);
                eventSymbolByConstant.put(
                        constantKey(ref.getQualifiedName(), v.getSimpleName()), symbol);
            }
            return out;
        }
        // A member that *carries* an enum — `record Send(Signal signal)` — is not one
        // input but a family of them. The real input is the pair, so Σ is the pair:
        // {Send.HEADERS, Send.PUSH_PROMISE, ...}. This is the same closed-world move
        // as the enum-member case one level down, and it is what makes an event
        // modelled as `Send(HEADERS)` comparable with one modelled as a flat
        // `SEND_HEADERS` constant — the two spellings then yield the same |Σ|.
        //
        // Deliberately NOT registered in eventSymbolByConstant: that table is keyed
        // by the constant's owning type, and the same `Signal.HEADERS` occurs under
        // both `Send` and `Recv`. The prefix is only knowable at the pattern that
        // deconstructed the event, so the label is composed there instead.
        CtEnum<?> component = soleEnumComponent(member);
        if (component != null) {
            for (CtEnumValue<?> v : component.getEnumValues()) {
                out.add(composedSymbol(ref.getSimpleName(), v.getSimpleName()));
            }
            return out;
        }
        out.add(ref.getSimpleName());
        return out;
    }

    /**
     * The single enum-typed component of an event member, or {@code null} when it
     * has none or several. Several is ambiguous — nothing in the shape says which
     * one discriminates the input — so Σ stays at the member itself rather than
     * guessing one.
     */
    private static CtEnum<?> soleEnumComponent(CtType<?> member) {
        if (member == null) return null;
        CtEnum<?> found = null;
        try {
            for (CtField<?> f : member.getFields()) {
                if (f.isStatic()) continue;
                CtTypeReference<?> t = f.getType();
                CtType<?> decl = t == null ? null : t.getTypeDeclaration();
                if (decl instanceof CtEnum<?> en && !en.getEnumValues().isEmpty()) {
                    if (found != null) return null; // ambiguous
                    found = en;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return found;
    }

    /**
     * How a qualified event symbol is spelled, in ONE place. Σ enumeration and edge
     * labelling both go through it, so the alphabet and the labels cannot drift
     * apart into two spellings of the same input.
     */
    private static String composedSymbol(String prefix, String constant) {
        return prefix + "." + constant;
    }

    private static String constantKey(String ownerQualifiedName, String constant) {
        return ownerQualifiedName + "#" + constant;
    }

    // ---- functional transition callables (F7) --------------------------------

    /**
     * Walk a transition callable supplied as a functional value — a lambda or an
     * anonymous-class method — rather than a named function. The algorithm is the
     * same as for a named centralized function (dispatch on a selector, produce
     * the next state per arm); only the <em>mechanism</em> differs: the from-state
     * is attributed through {@code if (selector instanceof T)} tests instead of a
     * {@code switch}, and the event label is the enclosing method's name.
     *
     * <p>The selector is the callable's root-typed input. At entry it could be any
     * permitted subtype, or nothing yet ({@link StateMachine#INITIAL_PSEUDO_STATE
     * machine entry}); each {@code instanceof} test narrows this from-set as the
     * walk descends.
     */
    private void extractFunctional(CtElement callable, Set<Transition> out) {
        List<CtParameter<?>> params = callableParameters(callable);
        CtVariable<?> sel = selectorParameter(params);
        if (sel == null) {
            // No hierarchy-typed input: not a dispatch we can attribute. Record
            // nothing rather than guess — the callable is left as a gap.
            return;
        }
        CtElement body = callableBody(callable);
        if (body == null) return;

        commitForms.add(CommitForm.VALUE_RETURN);
        this.selector = sel;
        try {
            walkFunctional(body, FromCtx.all(concreteStateSimpleNames), functionalEventLabel(callable), null, out);
        } finally {
            this.selector = null;
        }
    }

    /**
     * The from-context of a functional-callable walk: the set of concrete states
     * the selector may still be on this path, plus whether the machine-entry
     * residual (selector matched no permitted subtype) is included. A single
     * producer fires one edge per possible from-state.
     */
    private record FromCtx(Set<String> states, boolean entry) {
        static FromCtx all(Set<String> states) {
            return new FromCtx(new LinkedHashSet<>(states), true);
        }
        static FromCtx of(String state) {
            return new FromCtx(new LinkedHashSet<>(Set.of(state)), false);
        }
        FromCtx without(String t) {
            Set<String> s = new LinkedHashSet<>(states);
            s.remove(t);
            return new FromCtx(s, entry);
        }
    }

    /**
     * Descend a functional callable's control flow, attributing the from-state
     * through {@code instanceof} dispatch and threading the entry/residual set.
     */
    private void walkFunctional(CtElement node, FromCtx ctx, String event, String guard, Set<Transition> out) {
        if (node == null) return;

        if (node instanceof CtBlock<?> block) {
            walkFunctionalBlock(block, ctx, event, guard, out);
        } else if (node instanceof CtIf ctIf) {
            String subtype = selectorInstanceOfSubtype(ctIf.getCondition());
            if (subtype != null) {
                // Type test on the selector: the then-branch runs with from = T and
                // the instanceof itself is NOT recorded as a data guard (F7 rule 3).
                walkFunctional(ctIf.getThenStatement(), FromCtx.of(subtype), event, guard, out);
                if (ctIf.getElseStatement() != null) {
                    walkFunctional(ctIf.getElseStatement(), ctx.without(subtype), event, guard, out);
                }
            } else {
                // Ordinary data guard: split the path condition, from-set unchanged.
                String cond = safeText(ctIf.getCondition());
                walkFunctional(ctIf.getThenStatement(), ctx, event, merge(guard, cond), out);
                walkFunctional(ctIf.getElseStatement(), ctx, event, merge(guard, negate(cond)), out);
            }
        } else if (node instanceof CtSwitch<?> sw) {
            // A switch inside the callable dispatches on the state itself; reuse the
            // shared switch attribution (from = matched pattern), ignoring the set.
            walkSwitch(sw, null, event, guard, out);
        } else if (node instanceof CtReturn<?> ret) {
            handleFunctionalValue(ret.getReturnedExpression(), ctx, event, guard, out);
        } else if (node instanceof CtYieldStatement ys) {
            handleFunctionalValue(ys.getExpression(), ctx, event, guard, out);
        } else if (node instanceof CtExpression<?> expr && isHierarchyTyped(expr)) {
            // An expression statement / arrow body whose static type is within the
            // hierarchy is a producer. Every other bare statement — a void call, a
            // collection mutation, a flag write — is an ACTION and skipped (F7
            // rule 6): the exclusion is derived from the type, not enumerated.
            handleFunctionalValue(expr, ctx, event, guard, out);
        }
    }

    /**
     * Walk a functional block, threading the entry/residual from-set and the
     * data-guard fall-through across siblings. When an {@code if (selector
     * instanceof T)} definitely terminates its then-branch, subsequent siblings
     * exclude T from the residual; when it falls through, T stays reachable and
     * the set is unchanged (F7 rule 4).
     */
    private void walkFunctionalBlock(CtBlock<?> block, FromCtx ctx, String event,
                                     String guard, Set<Transition> out) {
        FromCtx acc = ctx;
        String accGuard = guard;
        for (CtStatement st : block.getStatements()) {
            walkFunctional(st, acc, event, accGuard, out);
            if (st instanceof CtIf ctIf) {
                String subtype = selectorInstanceOfSubtype(ctIf.getCondition());
                boolean noElse = ctIf.getElseStatement() == null;
                boolean thenTerminates = alwaysTerminates(ctIf.getThenStatement());
                if (subtype != null) {
                    if (noElse && thenTerminates) acc = acc.without(subtype);
                } else if (noElse && thenTerminates) {
                    accGuard = merge(accGuard, negate(safeText(ctIf.getCondition())));
                }
            } else if (alwaysTerminates(st)) {
                break; // remaining statements are unreachable
            }
        }
    }

    /** Resolve one produced value against every possible from-state in the set. */
    private void handleFunctionalValue(CtExpression<?> value, FromCtx ctx, String event,
                                       String guard, Set<Transition> out) {
        if (value == null) return;
        for (String from : ctx.states()) {
            handleValue(value, from, event, guard, out);
        }
        if (ctx.entry()) {
            handleEntryValue(value, event, guard, out);
        }
    }

    /**
     * A producer reached on the entry residual (selector matched no permitted
     * subtype) establishes the machine's <em>initial</em> state: emit it as an
     * edge from {@link StateMachine#INITIAL_PSEUDO_STATE} (F7 rule 4). A bare
     * selector return here is the identity — there is no concrete initial target —
     * and is skipped rather than fabricated as a self-loop.
     */
    private void handleEntryValue(CtExpression<?> value, String event, String guard, Set<Transition> out) {
        if (isSelectorExpr(value)) return;
        if (value instanceof CtSwitchExpression<?, ?> sw) {
            walkFunctional(sw, new FromCtx(Set.of(), true), event, guard, out);
            return;
        }
        for (TransitionResolver.Candidate cand : resolver.resolve(value, null)) {
            String g = merge(guard, cand.guard());
            if (cand.resolved()) {
                out.add(Transition.resolved(StateMachine.INITIAL_PSEUDO_STATE,
                        cand.targetSimpleName(), event, g).withForm(cand.form()));
            } else {
                out.add(Transition.unresolved(StateMachine.INITIAL_PSEUDO_STATE, event, g, cand.raw()));
            }
        }
    }

    // ---- selector / instanceof attribution (shared F7 rule) ------------------

    /**
     * If {@code cond} is {@code <selector> instanceof T} where T is a permitted
     * subtype, return T's simple name; otherwise {@code null}. This is the single
     * type-test → from-state rule shared with the {@code switch} case attribution:
     * whether the type test is written as a {@code switch} pattern or an
     * {@code instanceof} does not change which subtype it selects.
     */
    private String selectorInstanceOfSubtype(CtExpression<?> cond) {
        if (!(cond instanceof CtBinaryOperator<?> bin)) return null;
        if (bin.getKind() != BinaryOperatorKind.INSTANCEOF) return null;
        if (!isSelectorExpr(bin.getLeftHandOperand())) return null;
        CtTypeReference<?> t = instanceofType(bin.getRightHandOperand());
        if (t != null && hierarchyQualifiedNames.contains(t.getQualifiedName())
                && !t.getQualifiedName().equals(rootQualifiedName)) {
            return stateId(t);
        }
        return null;
    }

    /**
     * The type an {@code instanceof} tests for, whether unbound
     * ({@code x instanceof T}, a {@link CtTypeAccess}) or a bound type pattern
     * ({@code x instanceof T t}, read reflectively via {@link #patternType}).
     */
    private CtTypeReference<?> instanceofType(CtExpression<?> rhs) {
        if (rhs == null) return null;
        if (rhs instanceof CtTypeAccess<?> ta) return ta.getAccessedType();
        CtTypeReference<?> pt = patternType(rhs);
        if (pt != null) return pt;
        return rhs.getType();
    }

    /** True when {@code e} reads the current selector variable. */
    private boolean isSelectorExpr(CtExpression<?> e) {
        if (selector == null || !(e instanceof CtVariableAccess<?> va) || va.getVariable() == null) {
            return false;
        }
        CtVariableReference<?> vref = va.getVariable();
        if (vref.getDeclaration() == selector) return true;
        return selector.getSimpleName().equals(vref.getSimpleName());
    }

    private List<CtParameter<?>> callableParameters(CtElement callable) {
        if (callable instanceof CtMethod<?> m) return m.getParameters();
        if (callable instanceof CtLambda<?> l) return l.getParameters();
        return List.of();
    }

    private CtElement callableBody(CtElement callable) {
        if (callable instanceof CtMethod<?> m) return m.getBody();
        if (callable instanceof CtLambda<?> l) {
            return l.getBody() != null ? l.getBody() : l.getExpression();
        }
        return null;
    }

    /**
     * The selector parameter: the input whose declared type is the sealed root
     * (the value the callable dispatches on). Falls back to any hierarchy-typed
     * parameter if none is exactly the root.
     */
    private CtVariable<?> selectorParameter(List<CtParameter<?>> params) {
        for (CtParameter<?> p : params) {
            CtTypeReference<?> t = p.getType();
            if (t != null && rootQualifiedName.equals(t.getQualifiedName())) return p;
        }
        for (CtParameter<?> p : params) {
            CtTypeReference<?> t = p.getType();
            if (t != null && hierarchyQualifiedNames.contains(t.getQualifiedName())) return p;
        }
        return null;
    }

    /**
     * Event label for a functional callable (F7 rule 7): the simple name of the
     * enclosing method that supplies it, subject to the same discrimination test
     * every other method name is (F22) — one supplier means one transition
     * function, and its name is not an input symbol.
     */
    private String functionalEventLabel(CtElement callable) {
        return functionalNamesDiscriminate ? enclosingMethodName(callable) : null;
    }

    /** Simple name of the method a functional callable is written inside, if any. */
    private static String enclosingMethodName(CtElement callable) {
        try {
            CtMethod<?> enclosing = callable.getParent(CtMethod.class);
            return enclosing == null ? null : enclosing.getSimpleName();
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- polymorphic carrier encoding (F8) -----------------------------------

    /**
     * Recover transitions for the polymorphic State pattern with a <em>carrier</em>
     * result (finding F8): each permitted subtype overrides a transition method
     * that returns the successor wrapped in a non-hierarchy object rather than
     * returning the hierarchy type itself.
     *
     * <pre>{@code
     *   public Transition on(Event event) {
     *       if (event instanceof SegmentArrival seg && seg.rst())
     *           return Transition.to(new Closed(), Action.SIGNAL_ABORT);
     *       if (event == UserCall.CLOSE)
     *           return Transition.to(new LastAck(), Action.SND_FIN);
     *       return Transition.ignore(this);
     *   }
     * }</pre>
     *
     * <p>The from-state needs no data-flow at all — it is the declaring class, and
     * is therefore as exact as the state set itself. Only the targets are
     * approximate, and they are recovered by descending <em>one</em> level into the
     * carrier's argument list. That bound is deliberate: a successor produced by a
     * helper call is recorded unresolved rather than chased, so the carrier
     * encoding inherits the same soundness invariant as the rest of the tool.
     */
    private void extractCarrier(CtMethod<?> m, Set<Transition> out) {
        CtType<?> declaring = m.getDeclaringType();
        if (declaring == null) return;
        CtTypeReference<?> ret = m.getType();
        if (ret != null && hierarchyQualifiedNames.contains(ret.getQualifiedName())) {
            return; // returns the hierarchy type — the distributed walker owns it
        }
        commitForms.add(CommitForm.POLY_CARRIER);
        dispatchedStates.add(stateId(declaring));
        // Σ and the event-parameter names are per-method, and drive the event
        // attribution performed by splitEventCondition below.
        enumerateEventAlphabet(m);
        try {
            walkCarrier(m.getBody(), stateId(declaring), null, null, out);
        } finally {
            eventParamNames = new LinkedHashSet<>();
        }
    }

    /**
     * Descend a carrier transition method, accumulating the guard and the
     * triggering event. Structurally this is {@link #walk} with two differences:
     * an {@code if} whose condition tests the event parameter contributes an
     * <em>event</em> rather than a guard, and a produced value is unwrapped from
     * its carrier by {@link #handleCarrierValue}.
     *
     * <p>Only {@code return}/{@code yield} produce a next state here. Every other
     * statement — a void call, a log line, a field write — is an ACTION and is
     * skipped, which is what keeps {@code Transition.to(new Closed(),
     * Action.SIGNAL_ABORT)}'s action list out of the transition relation.
     */
    private void walkCarrier(CtElement node, String from, String event, String guard, Set<Transition> out) {
        if (node == null) return;

        if (node instanceof CtBlock<?> block) {
            String acc = guard;
            boolean enteredOtherwise = otherwisePath;
            try {
                for (CtStatement st : block.getStatements()) {
                    walkCarrier(st, from, event, acc, out);
                    if (st instanceof CtIf ctIf
                            && ctIf.getElseStatement() == null
                            && alwaysTerminates(ctIf.getThenStatement())) {
                        // `if (c) return ...;` — every later sibling is reached only
                        // when c is false, so the guards stay mutually exclusive.
                        acc = merge(acc, negate(safeText(ctIf.getCondition())));
                    } else if (alwaysTerminates(st)) {
                        break; // remaining statements are unreachable
                    }
                    if (st instanceof CtIf || st instanceof CtSwitch<?>) {
                        // Anything after a conditional is on its fall-through path:
                        // it runs precisely when none of the branches above took.
                        // That is the definition of the default edge, and it holds
                        // whether or not the branch above could be negated into a
                        // guard — `if (e instanceof Seg) { ... }` followed by
                        // `return ignore(this);` is the common shape where it
                        // cannot, yet the trailing return is still the default.
                        otherwisePath = true;
                    }
                }
            } finally {
                otherwisePath = enteredOtherwise;
            }
        } else if (node instanceof CtIf ctIf) {
            EventCond ec = splitEventCondition(ctIf.getCondition());
            String thenGuard = merge(guard, ec.residual());
            if (ec.symbols().isEmpty()) {
                walkCarrier(ctIf.getThenStatement(), from, event, thenGuard, out);
            } else {
                // A disjunction of event tests (`e == CLOSE || e == USER`) triggers
                // one edge per symbol: they are distinct inputs of Σ, not one edge
                // with a compound label.
                for (String sym : ec.symbols()) {
                    walkCarrier(ctIf.getThenStatement(), from, sym, thenGuard, out);
                }
            }
            // The else branch keeps the inherited event and carries the negation of
            // the *whole* condition — negating only the residual would be wrong
            // once part of the condition was consumed as an event.
            if (ctIf.getElseStatement() != null) {
                boolean prev = otherwisePath;
                otherwisePath = true;
                try {
                    walkCarrier(ctIf.getElseStatement(), from, event,
                            merge(guard, negate(safeText(ctIf.getCondition()))), out);
                } finally {
                    otherwisePath = prev;
                }
            }
        } else if (node instanceof CtSwitch<?> sw) {
            walkCarrierSwitch(sw, from, event, guard, out);
        } else if (node instanceof CtReturn<?> ret) {
            handleCarrierValue(ret.getReturnedExpression(), from, event, guard, out);
        } else if (node instanceof CtYieldStatement ys) {
            handleCarrierValue(ys.getExpression(), from, event, guard, out);
        } else if (node instanceof CtTry tryStmt) {
            walkCarrier(tryStmt.getBody(), from, event, guard, out);
            for (CtCatch cc : tryStmt.getCatchers()) {
                walkCarrier(cc.getBody(), from, event, merge(guard, catchGuard(cc)), out);
            }
            walkCarrier(tryStmt.getFinalizer(), from, event, guard, out);
        } else if (node instanceof CtLoop loop) {
            walkCarrier(loop.getBody(), from, event, merge(guard, loopGuard(loop)), out);
        }
    }

    /** A switch inside a carrier method dispatches on the event, never on the state. */
    private void walkCarrierSwitch(CtAbstractSwitch<?> sw, String from, String event,
                                   String guard, Set<Transition> out) {
        boolean overEvent = isEventDispatch(sw);
        Map<String, List<String>> guardsByLabel = new LinkedHashMap<>();
        for (CtCase<?> c : sw.getCases()) {
            List<String> caseEvents = overEvent ? caseEventNames(c) : List.of();
            if (caseEvents.isEmpty()) {
                caseEvents = Collections.singletonList(event); // default arm inherits
            }
            // F12 applies here too: a later arm with the same label is reached
            // only when the earlier guard was false.
            String ownGuard = caseGuard(c);
            String caseGuard = merge(merge(guard, priorExclusion(caseEvents, guardsByLabel)), ownGuard);
            if (ownGuard != null) {
                for (String label : caseEvents) {
                    guardsByLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(ownGuard);
                }
            }
            boolean prev = otherwisePath;
            // A `default:` arm names no label, so whatever it produces is the
            // residual of every labelled arm — the default edge.
            if (c.getCaseExpressions().isEmpty()) otherwisePath = true;
            try {
                for (String caseEvent : caseEvents) {
                    for (CtStatement st : c.getStatements()) {
                        walkCarrier(st, from, caseEvent, caseGuard, out);
                    }
                }
            } finally {
                otherwisePath = prev;
            }
        }
    }

    /**
     * Resolve one value returned by a carrier transition method to its target
     * state(s), unwrapping the carrier when there is one.
     *
     * <p>Three cases, in order:
     * <ol>
     *   <li>the value <em>is</em> the successor — {@code return new Listen();} or
     *       {@code return this;} — resolved by the ordinary resolver;</li>
     *   <li>the value is a shallow carrier — {@code Transition.to(new LastAck(),
     *       ...)} — so each hierarchy-typed <em>direct</em> argument is a target;</li>
     *   <li>neither, meaning the successor cannot be determined without leaving
     *       this method: recorded UNRESOLVED, never guessed and never dropped.</li>
     * </ol>
     *
     * <p>F21 — before any of that, the value must be a value at all. See
     * {@link #callsNonReturningHelper}: a call that cannot return is not a
     * carrier, and the one-level unwrap below cannot tell one from the other.
     */
    private void handleCarrierValue(CtExpression<?> value, String from, String event,
                                    String guard, Set<Transition> out) {
        if (value == null) return;

        // (0) F21: the call never returns, so it wraps nothing and yields nothing.
        // The arm is an undefined input, exactly as `throw illegal(...)` would be.
        if (callsNonReturningHelper(value)) {
            nonReturningCalls++;
            return;
        }

        // `cond ? Transition.to(new Listen()) : Transition.to(new Closed(), ...)`
        if (value instanceof CtConditional<?> cond) {
            String c = safeText(cond.getCondition());
            handleCarrierValue(cond.getThenExpression(), from, event, merge(guard, c), out);
            handleCarrierValue(cond.getElseExpression(), from, event, merge(guard, negate(c)), out);
            return;
        }
        if (value instanceof CtSwitchExpression<?, ?> sw) {
            walkCarrierSwitch(sw, from, event, guard, out);
            return;
        }
        // (1) bare hierarchy value.
        if (isCarrierStateValue(value)) {
            handleValue(value, from, event, guard, out);
            return;
        }
        // (2) shallow carrier: descend ONE level into its arguments and no further.
        List<CtExpression<?>> args = carrierArguments(value);
        boolean unwrapped = false;
        for (CtExpression<?> arg : args) {
            if (!isCarrierStateValue(arg)) continue;
            unwrapped = true;
            handleValue(arg, from, event, guard, out);
        }
        if (unwrapped) return;
        // (3) the successor is computed elsewhere (`Transition.to(nextFor(event))`)
        // or the shape is unrecognised. Record the gap honestly.
        out.add(mark(Transition.unresolved(from == null ? "<unknown>" : from,
                event, guard, safeText(value)), event));
    }

    /** Is {@code e} a hierarchy value — {@code this}, {@code new S(...)}, or an H-typed read? */
    private boolean isCarrierStateValue(CtExpression<?> e) {
        if (e == null) return false;
        if (e instanceof CtThisAccess<?>) return true;
        if (e instanceof CtConstructorCall<?> cc) {
            CtTypeReference<?> t = cc.getType();
            return t != null && hierarchyQualifiedNames.contains(t.getQualifiedName());
        }
        return isHierarchyTyped(e);
    }

    /**
     * The argument list of a <em>carrier</em>: a call whose own result type sits
     * outside the hierarchy, so it wraps rather than composes states. Anything
     * else yields an empty list, which routes the value to the unresolved branch.
     */
    private List<CtExpression<?>> carrierArguments(CtExpression<?> value) {
        List<CtExpression<?>> args;
        if (value instanceof CtInvocation<?> inv) {
            args = new ArrayList<>(inv.getArguments());
        } else if (value instanceof CtConstructorCall<?> cc) {
            args = new ArrayList<>(cc.getArguments());
        } else {
            return List.of();
        }
        return isHierarchyTyped(value) ? List.of() : args;
    }

    // ---- event attribution from guards (F8) ----------------------------------

    /**
     * A condition split into the events it selects and the residual data guard.
     * {@code event instanceof SegmentArrival seg && seg.rst()} yields the symbol
     * {@code SegmentArrival} and the residual {@code seg.rst()}.
     */
    private record EventCond(Set<String> symbols, String residual) {
        static EventCond none(String text) {
            return new EventCond(Set.of(), text);
        }
    }

    /**
     * Separate the event tests in a condition from the rest of it, so the
     * triggering input becomes the edge's Σ label and only the genuine data
     * predicate remains as the guard.
     *
     * <p>Conjunction splits both sides. Disjunction only splits when <em>both</em>
     * sides are pure event tests — {@code e == CLOSE || e == USER} is two inputs,
     * but {@code e == CLOSE || retries > 3} is one opaque predicate and is kept
     * whole as a guard rather than being mis-attributed to an event.
     */
    private EventCond splitEventCondition(CtExpression<?> cond) {
        if (cond == null) return EventCond.none(null);

        if (cond instanceof CtBinaryOperator<?> bin) {
            BinaryOperatorKind kind = bin.getKind();
            if (kind == BinaryOperatorKind.AND) {
                EventCond l = splitEventCondition(bin.getLeftHandOperand());
                EventCond r = splitEventCondition(bin.getRightHandOperand());
                Set<String> syms = new LinkedHashSet<>(l.symbols());
                syms.addAll(r.symbols());
                return new EventCond(syms, merge(l.residual(), r.residual()));
            }
            if (kind == BinaryOperatorKind.OR) {
                EventCond l = splitEventCondition(bin.getLeftHandOperand());
                EventCond r = splitEventCondition(bin.getRightHandOperand());
                boolean pure = !l.symbols().isEmpty() && !r.symbols().isEmpty()
                        && l.residual() == null && r.residual() == null;
                if (pure) {
                    Set<String> syms = new LinkedHashSet<>(l.symbols());
                    syms.addAll(r.symbols());
                    return new EventCond(syms, null);
                }
                return EventCond.none(safeText(cond));
            }
        }
        String symbol = eventSymbolOf(cond);
        return symbol == null ? EventCond.none(safeText(cond))
                              : new EventCond(new LinkedHashSet<>(Set.of(symbol)), null);
    }

    /**
     * The Σ symbol a leaf condition names, or {@code null} when it tests something
     * other than the event: {@code event instanceof SegmentArrival} →
     * {@code SegmentArrival}; {@code event == UserCall.CLOSE} → {@code UserCall.CLOSE}.
     */
    private String eventSymbolOf(CtExpression<?> cond) {
        if (!(cond instanceof CtBinaryOperator<?> bin)) return null;
        BinaryOperatorKind kind = bin.getKind();
        CtExpression<?> lhs = bin.getLeftHandOperand();
        CtExpression<?> rhs = bin.getRightHandOperand();

        if (kind == BinaryOperatorKind.INSTANCEOF) {
            if (!isEventParamExpr(lhs)) return null;
            CtTypeReference<?> t = instanceofType(rhs);
            return t != null && eventQualifiedNames.contains(t.getQualifiedName())
                    ? t.getSimpleName() : null;
        }
        if (kind == BinaryOperatorKind.EQ) {
            if (isEventParamExpr(lhs)) return enumConstantSymbol(rhs);
            if (isEventParamExpr(rhs)) return enumConstantSymbol(lhs);
        }
        return null;
    }

    /**
     * The Σ symbol denoted by an enum-constant read such as {@code UserCall.CLOSE}.
     * Looked up in the table built while Σ was enumerated rather than re-derived,
     * so an edge label is spelled exactly as the alphabet spells it — bare
     * ({@code ARM}) when the parameter is itself the enum, qualified
     * ({@code UserCall.CLOSE}) when the enum is one member of a sealed event type.
     * A constant that is not in Σ yields {@code null} and is treated as a guard.
     */
    private String enumConstantSymbol(CtExpression<?> e) {
        if (!(e instanceof CtFieldAccess<?> fa) || fa.getVariable() == null) return null;
        CtFieldReference<?> fref = fa.getVariable();
        CtTypeReference<?> owner = fref.getDeclaringType();
        if (owner == null) owner = fref.getType(); // noClasspath fallback: the constant's own type
        if (owner == null) return null;
        return eventSymbolByConstant.get(constantKey(owner.getQualifiedName(), fref.getSimpleName()));
    }

    /** True when {@code e} reads one of the current method's event parameters. */
    private boolean isEventParamExpr(CtExpression<?> e) {
        return e instanceof CtVariableAccess<?> va
                && va.getVariable() != null
                && eventParamNames.contains(va.getVariable().getSimpleName());
    }

    // ---- mutation / GoF State encoding (F2) ----------------------------------

    /**
     * Recover transitions expressed by <em>mutating</em> a state field rather
     * than returning the next state — the classic GoF State-pattern family:
     * {@code this.state = new Locked();} or {@code ctx.setState(new Locked());}.
     * The from-state is the switch arm when the method dispatches on the state
     * field, otherwise the declaring state class (GoF callbacks); the to-state is
     * the assigned value / mutator argument, resolved like any produced value.
     */
    private void extractMutationEncoding(CtType<?> root, CtModel model, Set<Transition> out) {
        stateFieldNames = findStateFieldNames(model);
        Set<String> keys = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        for (CtMethod<?> mutator : findMutators(model)) {
            keys.add(methodKey(mutator));
            names.add(mutator.getSimpleName());
        }
        mutatorKeys = keys;
        mutatorNames = names;
        if (stateFieldNames.isEmpty() && mutatorKeys.isEmpty()) return;
        if (!mutatorKeys.isEmpty()) {
            diagnostics.add("mutation encoding: the commit channel is " + mutatorNames + ", "
                    + "recognised by SHAPE alone — one hierarchy-typed parameter, committed to a "
                    + "hierarchy-typed field. The spelling is recorded here as corroboration; no "
                    + "name is consulted to reach it, so a mutator called anything at all is "
                    + "found and one merely NAMED like one is not (F22)");
        }

        commitForms.add(CommitForm.FIELD_MUTATION);
        WalkSite saved = this.walkSite;
        this.walkSite = new WalkSite(Route.MUTATION_FALLBACK, null, CommitForm.FIELD_MUTATION);
        try {
            for (CtMethod<?> m : findMutationMethods(model)) {
                CtType<?> declaring = m.getDeclaringType();
                // GoF callback (`class Closed { void onLock(ctx){ ctx.setState(...); } }`):
                // the from-state is the declaring state class. A method that
                // dispatches on the state field instead leaves from null here and
                // has it set per matched arm by walkSwitch.
                String from = declaring != null
                        && hierarchyQualifiedNames.contains(declaring.getQualifiedName())
                        ? stateId(declaring)
                        : null;
                if (from != null) dispatchedStates.add(from);
                // Mutation-style event labelling is future work (as for
                // centralized), so the event label stays null.
                walk(m.getBody(), from, null, null, out);
            }
        } finally {
            this.walkSite = saved;
        }
    }

    /** Simple names of fields whose declared type is inside the hierarchy. */
    private Set<String> findStateFieldNames(CtModel model) {
        Set<String> names = new LinkedHashSet<>();
        for (CtField<?> f : model.getElements(new TypeFilter<>(CtField.class))) {
            CtTypeReference<?> t = f.getType();
            if (t != null && hierarchyQualifiedNames.contains(t.getQualifiedName())) {
                names.add(f.getSimpleName());
            }
        }
        return names;
    }

    /**
     * The hierarchy's <em>state mutators</em>: the methods through which a
     * {@code ctx.setState(new Locked())} call site commits a successor.
     *
     * <p><b>F22 — a mutator is a shape, not a vocabulary.</b> This admitted a
     * method on either of two grounds: it assigned a state field, <em>or</em> its
     * name was one of {@code setState}/{@code changeState}/{@code transitionTo}/
     * {@code goTo}/{@code setCurrent}/{@code become}. The second disjunct made a
     * word list load-bearing in a recognizer whose sibling
     * ({@link CarrierTransitionDetector}) states in its own contract that nothing
     * keys off a name. Both could not be true, and the name half was not merely
     * inelegant: an admitted method has its call sites' ARGUMENT published as the
     * committed successor, so {@code void become(Bolt observed) { this.log =
     * observed.toString(); }} — an audit hook that commits nothing — turned every
     * {@code become(current)} in the model into a RESOLVED self-loop. A fabricated
     * resolved edge is the one failure mode the soundness invariant forbids
     * outright.
     *
     * <p>Recognition is now structural and consults no name at all: exactly one
     * parameter, its type inside the hierarchy, and a body that assigns a
     * hierarchy-typed field an expression in which <em>that parameter is the only
     * hierarchy value</em> ({@link #commitsFrom}).
     *
     * <p>The last clause is the half a plain "writes an H-typed field" rule misses,
     * and it is what actually licenses reading a call's argument as the successor.
     * {@code void restart(Vent previous) { audit(previous); this.state = new
     * Sealed(); }} has one hierarchy-typed parameter and does write the state
     * field — so the naive structural rule admits it just as the word list did —
     * yet its argument is the state being LEFT, not the one being entered, and
     * every call site reported a confident edge to the wrong target. Excluded
     * here, such a method keeps its own commit: it is no longer a mutator, so
     * {@link #findMutationMethods} picks it up and walks it, and the successor it
     * really installs is recorded — unattributably, hence unresolved — instead of
     * being replaced by a fiction.
     */
    private List<CtMethod<?>> findMutators(CtModel model) {
        // A List in model order, not an identity-hashed Set. Identity is the right
        // EQUALITY here (Spoon gives CtElement deep structural equality, so two
        // distinct mutators with identical bodies would dedupe into one), but
        // IdentityHashMap's iteration order follows identity hash codes, which vary
        // between JVM runs. Those names reach a diagnostic, so the tool's output
        // text was not reproducible: the same jar over the same sources printed
        // "[engage, assume]" one run and "[assume, engage]" the next. A result a
        // reader cannot diff against yesterday's is a research instrument with a
        // hole in it, and it also defeats any golden-file check. getElements walks
        // the model in source order, and each method appears once, so there is
        // nothing to dedupe and the order is the source's.
        List<CtMethod<?>> found = new ArrayList<>();
        for (CtMethod<?> m : model.getElements(new TypeFilter<>(CtMethod.class))) {
            if (m.getBody() == null) continue;
            List<CtParameter<?>> ps = m.getParameters();
            if (ps.size() != 1) continue;
            CtParameter<?> param = ps.get(0);
            CtTypeReference<?> pt = param.getType();
            if (pt == null || !hierarchyQualifiedNames.contains(pt.getQualifiedName())) continue;
            boolean writesState = false;
            boolean commits = false;
            for (CtAssignment<?, ?> a : m.getElements(new TypeFilter<>(CtAssignment.class))) {
                if (!isStateFieldWrite(a.getAssigned())) continue;
                writesState = true;
                if (commitsFrom(a.getAssignment(), param)) {
                    commits = true;
                    break;
                }
            }
            if (commits) {
                found.add(m);
            } else if (writesState) {
                unattributedMutators.add(m.getSimpleName());
            }
        }
        return found;
    }

    /**
     * Is {@code value} committed <em>from</em> {@code param} and from no other
     * hierarchy value? True for {@code next} and for a laundered read of it such as
     * {@code Objects.requireNonNull(next)}; false for {@code new Sealed()} (the
     * parameter is not read at all) and for {@code next.spent() ? new Spent() :
     * new Live()} (the parameter is read, but what lands in the field is chosen
     * here rather than by the caller).
     *
     * <p>Only hierarchy-typed <em>leaves</em> are counted — constructions, and
     * variable or field reads. An enclosing invocation is a transformation, not a
     * second source of state: were it counted, the laundered form above would be
     * rejected and every call site of a null-checking mutator would lose its edge
     * with no unresolved marker.
     */
    private boolean commitsFrom(CtExpression<?> value, CtParameter<?> param) {
        if (value == null) return false;
        boolean readsParameter = false;
        for (CtVariableAccess<?> va : value.getElements(new TypeFilter<>(CtVariableAccess.class))) {
            if (isReadOf(va, param)) {
                readsParameter = true;
            } else if (isHierarchyTyped(va)) {
                return false; // a second hierarchy value feeds the commit
            }
        }
        if (!readsParameter) return false;
        for (CtConstructorCall<?> cc : value.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (isHierarchyTyped(cc)) return false;
        }
        for (CtThisAccess<?> ta : value.getElements(new TypeFilter<>(CtThisAccess.class))) {
            if (isHierarchyTyped(ta)) return false;
        }
        return true;
    }

    /**
     * Does {@code access} read {@code param}? Matched on the declaration, with the
     * simple name as a prefilter — the F13 rule. The name is decisive only when
     * Spoon binds nothing, and that is safe HERE in a way it is not for a local:
     * Java forbids a method body from declaring a local that shadows a parameter,
     * so within this body a matching name can only be this parameter.
     */
    private static boolean isReadOf(CtVariableAccess<?> access, CtParameter<?> param) {
        CtVariableReference<?> ref = access.getVariable();
        if (ref == null || !param.getSimpleName().equals(ref.getSimpleName())) return false;
        CtVariable<?> decl = ref.getDeclaration();
        return decl == null || decl == param;
    }

    /**
     * Methods that produce a transition by mutation: they assign a state field or
     * call a mutator. The mutators themselves are excluded — a setter merely
     * stores its parameter and carries no next-state of its own.
     */
    private List<CtMethod<?>> findMutationMethods(CtModel model) {
        List<CtMethod<?>> out = new ArrayList<>();
        for (CtMethod<?> m : model.getElements(new TypeFilter<>(CtMethod.class))) {
            if (m.getBody() == null) continue;
            if (mutatorKeys.contains(methodKey(m))) {
                continue; // the mutator itself: it stores its parameter, it does not choose it
            }
            boolean writesField = m.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                    .anyMatch(a -> isStateFieldWrite(a.getAssigned()));
            boolean callsMutator = m.getElements(new TypeFilter<>(CtInvocation.class)).stream()
                    .anyMatch(this::isMutatorCall);
            if (writesField || callsMutator) out.add(m);
        }
        return out;
    }

    /**
     * Is {@code lhs} a write to <em>this</em> hierarchy's state field?
     *
     * <p>The field <em>name</em> is only a candidate filter. Unrelated machines
     * analysed in the same model routinely both call their field {@code state},
     * and matching on the name alone made every such machine absorb the other's
     * assignments as edges with an undetermined source — polluting the unresolved
     * count with transitions that belong to a different automaton. The declared
     * type is what actually decides, so it is checked here; the name lookup merely
     * avoids resolving types for every assignment in the model.
     *
     * <p>Under {@code noClasspath} an unresolvable field type falls back to the
     * name match. That is the direction that cannot silently drop a commit — an
     * unrecognised state-field write is not recorded as an unresolved edge, it is
     * simply not a transition site — but it is still a name standing in for a
     * proof, so F22 reports it rather than leaving it invisible.
     */
    private boolean isStateFieldWrite(CtExpression<?> lhs) {
        if (!(lhs instanceof CtVariableAccess<?> va) || va.getVariable() == null) {
            return false;
        }
        CtVariableReference<?> vref = va.getVariable();
        if (!stateFieldNames.contains(vref.getSimpleName())) return false;
        CtTypeReference<?> declared = vref.getType();
        if (declared == null) {
            unboundStateFieldWrites.add(vref.getSimpleName());
            return true; // type unresolvable — the direction that cannot drop a commit
        }
        return hierarchyQualifiedNames.contains(declared.getQualifiedName());
    }

    /**
     * Is {@code inv} a call to a recognised state mutator of <em>this</em>
     * hierarchy?
     *
     * <p>Decided on the callee's DECLARATION, keyed the way hosts are keyed
     * elsewhere ({@code declaringType#signature}), so the answer is the same set
     * {@link #findMutators} computed rather than a second, name-shaped
     * approximation of it. Two machines in one model routinely both expose a
     * {@code setState}, and the mutator's own body — not its name — is what says
     * which hierarchy it commits to.
     *
     * <p>The simple name is a PREFILTER only: it keeps the declaration lookup off
     * every unrelated call in the model. F22 removed the fallback beneath it,
     * which returned {@code true} on a name match whenever the callee's parameter
     * list came back empty — a condition that holds both for an unresolvable
     * reference and for a genuinely zero-argument method, and that published the
     * call's argument as a resolved successor on the strength of a word. When the
     * declaration cannot be bound the question is answered from the call's own
     * shape instead (one argument, hierarchy-typed) and the call is reported, so
     * the residual name match is visible rather than silent.
     */
    private boolean isMutatorCall(CtInvocation<?> inv) {
        try {
            CtExecutableReference<?> exe = inv.getExecutable();
            if (exe == null || !mutatorNames.contains(exe.getSimpleName())) return false;
            if (exe.getExecutableDeclaration() instanceof CtMethod<?> callee) {
                return mutatorKeys.contains(methodKey(callee));
            }
            List<CtExpression<?>> args = inv.getArguments();
            boolean shaped = args.size() == 1 && isHierarchyTyped(args.get(0));
            if (shaped) unboundMutatorCalls.add(exe.getSimpleName());
            return shaped;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Does {@code e} have a declared type inside the hierarchy? */
    private boolean isHierarchyTyped(CtExpression<?> e) {
        CtTypeReference<?> t = e.getType();
        return t != null && hierarchyQualifiedNames.contains(t.getQualifiedName());
    }

    // ---- inter-procedural resolution (F3) ------------------------------------

    /**
     * Fold a call to an in-model helper that returns the hierarchy type into its
     * possible return targets — a bounded, k-limited return-value summary. Returns
     * {@code true} when the call was handled — either folded (its resolvable
     * targets emitted, any unresolvable ones recorded) or shown to produce no
     * successor at all (F9, {@link #neverReturnsNormally}); {@code false} when it cannot be summarised
     * soundly — a library/abstract callee, a non-hierarchy return, an exhausted
     * depth budget, or a recursion cycle — leaving the caller to record it
     * unresolved (the never-guess invariant).
     */
    private boolean resolveInterprocedural(CtInvocation<?> inv, String from, String event,
                                           String guard, Set<Transition> out) {
        if (walkSite != null && walkSite.commit() == CommitForm.POLY_CARRIER) {
            // F8 scope boundary: the POLYMORPHIC_OVERRIDE carrier encoding is
            // analysed strictly intra-procedurally. A successor computed by a
            // helper stays UNRESOLVED — never followed, never guessed. CLAUDE.md
            // lists this as a v1 scope line, so it is left exactly where it was.
            //
            // It is keyed on POLY_CARRIER and not on "any carrier" deliberately.
            // The bound was a statement about that PATH — walkCarrier never had a
            // fold to disable — and reading it as a statement about carriers in
            // general would export it to a locus that has no reason for it: at a
            // centralized switch, `case Initial i -> handleInitial(i, e)` is the
            // ordinary factoring of a large table, and F3/F18 already fold exactly
            // that shape when the helper returns H. Declining only because the
            // helper returns a WRAPPER would mean the same source, refactored in
            // nothing but its return type, loses its entire relation — which is
            // the idiom-sensitivity the locus/commit split exists to remove.
            return false;
        }
        CtExecutableReference<?> exe = inv.getExecutable();
        if (exe == null) return false;
        CtTypeReference<?> ret = exe.getType();
        if (ret == null) return false;
        boolean producesState = hierarchyQualifiedNames.contains(ret.getQualifiedName());
        // A helper that returns the same CARRIER this dispatch commits through is
        // producing a state too — one slot further in. Its returns are unwrapped by
        // handleValue on the way back out, by the same one-level rule, so nothing
        // about the approximation's depth changes; only the shape of what the
        // budget is spent on does.
        boolean producesCarrier = !producesState && commitsThroughCarrier()
                && CommitClassifier.carrierComponentOf(ret, hierarchyQualifiedNames) != null;
        if (!producesState && !producesCarrier) {
            return false; // not a state-producing call
        }
        if (!(exe.getExecutableDeclaration() instanceof CtMethod<?> callee) || callee.getBody() == null) {
            return false; // library / abstract / unavailable in the model
        }
        String sig = callee.getSignature();
        if (interProcStack.size() >= MAX_INTERPROC_DEPTH || interProcStack.contains(sig)) {
            return false; // depth budget exhausted or recursion cycle
        }
        // F9: a callee that provably cannot return normally produces no successor,
        // so the arm holding this call carries no transition at all. This must be
        // tested BEFORE the summarisability check below: both fire on an empty
        // `collectReturns`, but they mean opposite things — "there is no target"
        // versus "there is a target we could not see".
        if (neverReturnsNormally(callee)) {
            nonReturningCalls++;
            return true; // handled: nothing to emit
        }
        // F18 — the summary is produced by the SAME walker that reads a host
        // body. `collectReturns` was a second, strictly weaker traversal of its
        // own: it descended blocks and `if`s and nothing else, so a helper whose
        // returns sat inside a `switch`, a loop or a `try` was not summarisable at
        // all — and `private H fromIdle(E e) { switch (e) { case START: return
        // ...; } }` is the ordinary way a large per-event table is factored, not
        // an exotic shape. Two walkers over one construct is the arrangement this
        // codebase already refuses elsewhere (one `chainOf`, one commit
        // predicate); reusing `walk` also hands a folded body every rule the
        // walker already knows — event labelling from case labels, F12 arm
        // exclusion, F10's synthetic yields, F6's exceptional flow — instead of
        // re-deriving them here and drifting.
        List<CtReturn<?>> owned = ownedReturns(callee);
        if (owned.isEmpty()) return false; // no return of its own: nothing to summarise

        Set<CtReturn<?>> enclosing = accountedReturns;
        Set<CtReturn<?>> accounted = Collections.newSetFromMap(new IdentityHashMap<>());
        accountedReturns = accounted;
        boolean top = interProcStack.isEmpty();
        int resolvedBefore = top ? countResolved(out) : 0;
        interProcStack.push(sig);
        try {
            // Walked in the *caller's* from-context, so a returned root-typed value
            // ("the current state") becomes a self-loop to the caller's from-state
            // exactly as in a direct transition method, and a returned call
            // recurses under the depth budget.
            walk(callee.getBody(), from, event, guard, out);
        } finally {
            interProcStack.pop();
            accountedReturns = enclosing;
            if (top) interProcResolvedEdges += Math.max(0, countResolved(out) - resolvedBefore);
        }

        // F18, and the half that makes this a soundness fix rather than only a
        // recall one: completeness is ASKED, not assumed. The old test was
        // `returns.isEmpty()`, which detects a summary that failed ENTIRELY and
        // says nothing about one that reached some returns and missed others. Such
        // a partial summary was folded and published as fact: one return inside a
        // loop and one after it — `for (...) { if (bad) return new Halted(); }
        // return new Running();` — reported a resolved self-loop and DROPPED the
        // Halted target, a real transition gone with no unresolved marker behind a
        // clean-looking n/n. Every return the walk neither reached nor proved dead
        // is now recorded as exactly what it is: a successor that exists and could
        // not be read.
        for (CtReturn<?> r : owned) {
            if (accounted.contains(r)) continue;
            unreadableReturns++;
            out.add(Transition.unresolved(from == null ? "<unknown>" : from, event, guard,
                    truncate(safeText(r))));
        }
        return true;
    }

    /**
     * The {@code return} statements that belong to {@code callee} itself, and so
     * are the successors a call to it can yield.
     *
     * <p>A {@code return} inside a lambda or a local/anonymous class is that
     * body's exit, not this method's: it is neither a successor of this call nor
     * something {@link #walk} is expected to reach, and counting it would report a
     * gap on every helper that uses a stream. (F9's own scan is deliberately
     * unfiltered for the opposite reason — there, counting a lambda's return makes
     * it DECLINE to suppress, which is the safe direction.) When the parent chain
     * cannot be read the return is kept, so an unwalked one is reported rather
     * than assumed away.
     */
    private static List<CtReturn<?>> ownedReturns(CtMethod<?> callee) {
        List<CtReturn<?>> owned = new ArrayList<>();
        for (CtReturn<?> r : callee.getBody().getElements(new TypeFilter<>(CtReturn.class))) {
            if (r.getReturnedExpression() == null) continue; // `return;` yields no successor
            try {
                if (r.getParent(CtExecutable.class) != callee) continue;
            } catch (Throwable ignored) {
                // unreadable parent chain: fall through and keep it
            }
            owned.add(r);
        }
        return owned;
    }

    /**
     * Record that the fold in progress has reached this return (F18). Called from
     * {@link #walk} before the value is descended, because descending may start a
     * nested fold that swaps the frame out.
     */
    private void noteAccounted(CtReturn<?> ret) {
        if (accountedReturns != null) accountedReturns.add(ret);
    }

    /**
     * Record every return inside a subtree the walk deliberately SKIPPED because
     * it cannot execute in this context — a switch arm for a state the selector
     * provably is not, a chain link testing a type already excluded (F18).
     *
     * <p>Not reaching such a return is a proof, not a gap. Without this the
     * completeness test cannot tell "we did not read it" from "it cannot run", and
     * every contextually-folded helper would grow a spurious unresolved edge.
     */
    private void markUnreachable(CtElement subtree) {
        if (accountedReturns == null || subtree == null) return;
        accountedReturns.addAll(subtree.getElements(new TypeFilter<>(CtReturn.class)));
    }

    /**
     * F9 — true when {@code callee} provably cannot complete normally, so a call
     * to it yields no successor state and contributes no transition.
     *
     * <p>This is <em>exact</em>, not a heuristic, and that is the whole reason it
     * is allowed to suppress an edge. JLS §8.4.7 makes it a compile-time error for
     * a method with a declared return type to have a body that can complete
     * normally. A body containing no {@code return} anywhere therefore
     * <em>cannot</em> return a value — it must throw or diverge. Either way there
     * is no state to transition to.
     *
     * <p>Non-voidness is that licence's precondition, so it is checked <em>here</em>
     * rather than left to the caller. A {@code void} method completes normally by
     * falling off the end of its body, so an empty one proves the opposite of what
     * this predicate reports. The inter-procedural caller establishes the point
     * incidentally (it has already required the return type to be in the
     * hierarchy); the carrier caller has not, and a predicate whose exactness
     * depends on which site invokes it is one waiting to be misused. For the same
     * reason the parameter is a {@code CtMethod}: a <em>constructor</em> contains
     * no {@code return} for a purely grammatical reason, so this scan would report
     * every one of them as non-returning.
     *
     * <p>It is the syntactic counterpart of the D3 rule that a {@code throw} arm
     * produces no edge. Without it, {@code default -> throw fail(s, e)} and
     * {@code default -> fail(s, e)} — where {@code fail} always throws — would
     * report different transition relations for the same machine, turning a
     * spelling difference into an apparent recall gap.
     *
     * <p>The scan is deliberately whole-body and unfiltered, so a {@code return}
     * nested in a lambda or local class counts as the method's own. That is the
     * conservative direction: it declines to suppress, falling back to the
     * previous unresolved-edge behaviour, and never drops a real transition.
     *
     * <p>F11 — the rule may only be applied to a body the analysis actually
     * <em>read</em>. For a method outside the source set (a JDK or library call),
     * Spoon supplies a reflective <em>shadow</em> declaration: a signature with an
     * empty {@code { }} body. That body contains no {@code return} for the same
     * reason it contains nothing at all — it was never parsed — so the emptiness
     * carries no information about whether the method returns. Reading it as proof
     * turned every library call returning a hierarchy type into a silently deleted
     * edge: {@code Objects.requireNonNull(state, ...)},
     * {@code Optional.orElse(new Closed())}, {@code map.getOrDefault(k, new Idle())}.
     * That is a false drop with no unresolved marker, the one outcome the
     * "unresolved transitions are never dropped" invariant forbids. The whole
     * licence for F9 to suppress is that JLS §8.4.7 makes the conclusion exact;
     * on a body that was never read there is no such licence.
     */
    private static boolean neverReturnsNormally(CtMethod<?> callee) {
        CtBlock<?> body = callee.getBody();
        if (body == null) return false;      // no body visible: never claim anything
        if (isShadow(callee)) return false;  // F11: a stub body is not an empty body
        if (isVoid(callee.getType())) return false;  // no JLS §8.4.7 licence to suppress
        return body.getElements(new TypeFilter<>(CtReturn.class)).isEmpty();
    }

    /**
     * F21 — {@link #neverReturnsNormally} asked of a produced <em>expression</em>:
     * is this value the result of calling a helper that provably cannot return?
     *
     * <p>The carrier path needs the question in this form because it has no fold
     * to hang it off. {@link #resolveInterprocedural} reaches F9 while summarising
     * a callee, and returns early in {@code carrierMode} — deliberately, since the
     * carrier encoding is strictly intra-procedural. But that scope line bounds
     * <em>approximation</em>: it says a successor computed inside a helper stays
     * unresolved rather than being guessed at. F9 is not an approximation and
     * guesses nothing; it is the compiler-checked observation that there is no
     * successor. Suppressing the fold therefore suppressed the wrong thing, and
     * the carrier path was left with no F9 at all.
     *
     * <p>What that cost is a fabricated <em>resolved</em> edge, the one failure
     * mode the soundness invariant forbids outright. {@code illegal(new Closed(),
     * event)} and {@code Transition.to(new Closed(), ...)} are the same shape — a
     * call whose own type is outside the hierarchy, carrying a hierarchy-typed
     * argument — and {@link #handleCarrierValue}'s one-level unwrap reads the
     * argument of either as the successor. Nothing in the expression distinguishes
     * them; only the callee's body does. The usual spelling of an undefined cell
     * is {@code illegal(this, event)}, so the usual fabrication is a self-loop,
     * and a specification with many undefined cells (RFC 1661's LCP has roughly
     * forty) grows one per cell — every one of them reported resolved, inflating
     * both the numerator and the denominator of the recall figure the thesis
     * reports. The suppressed calls are counted and surface as a diagnostic, the
     * same treatment F9 already gives them on the centralized path.
     *
     * <p>Restricted to a {@link CtInvocation} with a {@link CtMethod} declaration:
     * {@code new Foo(...)} contains no {@code return} for grammatical reasons, so
     * admitting a constructor here would suppress every construction in the
     * corpus. {@link #calleeMethod} already answers {@code null} for anything that
     * is not a method, and {@link #neverReturnsNormally} declines on an unread
     * (F11) or void body, so a library carrier such as {@code List.of(new Idle())}
     * keeps its edge.
     */
    private static boolean callsNonReturningHelper(CtExpression<?> value) {
        if (!(value instanceof CtInvocation<?> inv)) return false;
        CtMethod<?> callee = calleeMethod(inv);
        return callee != null && neverReturnsNormally(callee);
    }

    /** Best-effort {@code void} test; answers "void" when the type is unreadable. */
    private static boolean isVoid(CtTypeReference<?> type) {
        try {
            return type == null || "void".equals(type.getQualifiedName());
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * True when {@code type} was reconstructed by reflection rather than parsed
     * from the source set, so its body is a stub. Checked two ways because either
     * signal alone has failed across Spoon versions: {@code isShadow()} is the
     * declared API, and an invalid source position is the structural consequence
     * (verified — a JDK declaration reports {@code isShadow() == true} and
     * {@code getPosition().isValidPosition() == false}). Best-effort and never
     * throws; when unreadable it answers "shadow", because declining to apply F9
     * is the direction that cannot drop a transition.
     */
    private static boolean isShadow(CtElement element) {
        try {
            if (element instanceof CtShadowable s && s.isShadow()) return true;
            SourcePosition pos = element.getPosition();
            return pos == null || !pos.isValidPosition();
        } catch (Throwable t) {
            return true;
        }
    }

    private static CtMethod<?> calleeMethod(CtInvocation<?> inv) {
        try {
            CtExecutableReference<?> exe = inv.getExecutable();
            if (exe == null) return null;
            return exe.getExecutableDeclaration() instanceof CtMethod<?> m ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int countResolved(Set<Transition> out) {
        int n = 0;
        for (Transition t : out) if (t.isResolved()) n++;
        return n;
    }

    // ---- unified control-flow walk -------------------------------------------

    /**
     * Descends a method body (or a fragment of one) accumulating the guard under
     * which control reaches each next-state-producing expression.
     *
     * @param from      current source state, or {@code null} when not yet known
     * @param event     event label to attach, or {@code null}
     * @param guard     path condition accumulated from enclosing {@code if}s and
     *                  guarded {@code when} clauses, or {@code null}
     */
    private void walk(CtElement node, String from, String event, String guard, Set<Transition> out) {
        if (node == null) return;

        if (node instanceof CtBlock<?> block) {
            walkBlock(block, from, event, guard, out);
        } else if (node instanceof CtIf ctIf) {
            // A chain of type tests over the dispatch selector is a state
            // discrimination, not a condition: `if (s instanceof Closed)` selects
            // the from-state exactly the way `case Closed c ->` does, and reading
            // it as a guard both loses the source state and decorates every edge
            // with a predicate that is really the arm label. Handled as a unit so
            // the else branch knows which states are left.
            DispatchCommitDetector.TypeChain chain = typeChainAt(ctIf);
            if (chain != null) {
                walkTypeChain(ctIf, candidateStates(from), event, guard, out);
            } else {
                String cond = safeText(ctIf.getCondition());
                walk(ctIf.getThenStatement(), from, event, merge(guard, cond), out);
                walk(ctIf.getElseStatement(), from, event, merge(guard, negate(cond)), out);
            }
        } else if (node instanceof CtSwitch<?> sw) {
            walkSwitch(sw, from, event, guard, out);
        } else if (node instanceof CtReturn<?> ret) {
            // F18: tell the enclosing inter-procedural fold, if any, that this
            // return was reached. Recorded BEFORE the descent, because descending
            // may start a nested fold that swaps the frame out.
            noteAccounted(ret);
            handleValue(ret.getReturnedExpression(), from, event, guard, out);
        } else if (node instanceof CtYieldStatement ys) {
            handleValue(ys.getExpression(), from, event, guard, out);
        } else if (node instanceof CtAssignment<?, ?> asg) {
            // F2: an assignment to the *state field* is a transition site; the RHS
            // is the next-state expression (`this.state = new Locked();`). A bare
            // assignment to any other variable is a local mutation — read directly
            // by the F1 reaching-definitions pass — and not a produced value here.
            //
            // The second clause is the chain form of the same site. A dispatch the
            // detector accepted BECAUSE it writes an H-typed field commits once per
            // branch, so inside such a dispatch the write is the successor. It is
            // restricted to FIELD_MUTATION deliberately: a local accumulator is
            // read back later in the same body, and the reaching-definitions pass
            // (F1) resolves it there — honouring the write as well would report
            // that one successor twice.
            if ((inMutationFallback() && isStateFieldWrite(asg.getAssigned()))
                    || (dispatchCommit == CommitForm.FIELD_MUTATION
                        && DispatchCommitDetector.isCommitTarget(asg.getAssigned(),
                                                                 hierarchyQualifiedNames))) {
                handleValue(asg.getAssignment(), from, event, guard, out);
            }
        } else if (node instanceof CtInvocation<?> inv && inMutationFallback() && isMutatorCall(inv)) {
            // F2: ctx.setState(new Locked()) — the hierarchy-typed argument is the
            // next state (from-state is the enclosing arm / declaring state class).
            for (CtExpression<?> arg : inv.getArguments()) {
                if (isHierarchyTyped(arg) || inv.getArguments().size() == 1) {
                    handleValue(arg, from, event, guard, out);
                }
            }
        } else if (node instanceof CtTry tryStmt) {
            // F6: descend exceptional flow. The try body runs under the normal
            // guard; each catch under a synthetic "exception" guard (carrying the
            // caught type) so a catch-block producer — the classic error
            // transition, e.g. `catch (...) { yield new Failed(); }` — is
            // recovered rather than silently dropped. The finally block, which
            // always runs, is descended under the normal guard.
            walk(tryStmt.getBody(), from, event, guard, out);
            for (CtCatch cc : tryStmt.getCatchers()) {
                walk(cc.getBody(), from, event, merge(guard, catchGuard(cc)), out);
            }
            walk(tryStmt.getFinalizer(), from, event, guard, out);
        } else if (node instanceof CtLoop loop) {
            // F6: a loop merely repeats the same transitions, so descend its body
            // under the loop's entry condition. This is also the silent-miss
            // enabler for F1/F2 — a producer or state mutation inside the loop
            // would otherwise vanish with no unresolved marker.
            walk(loop.getBody(), from, event, merge(guard, loopGuard(loop)), out);
        } else if (node instanceof CtExpression<?> expr
                && isSwitchExpressionArm(expr) && isHierarchyTyped(expr)) {
            // Arrow-arm expression body in VALUE position: case X -> new A();
            // Spoon 10.4.2 wraps every arm form in a typed node (yield / block /
            // return) so this is currently unreachable, but the Spoon version is a
            // pom property meant to be bumped freely and an earlier modelling could
            // return. Keeping the branch — narrowed to the case where it would be
            // correct — means a version change is handled on purpose rather than by
            // accident. See F10 below for why the unnarrowed form was wrong.
            handleValue(expr, from, event, guard, out);
        }
        // F10 — every OTHER expression reaching here is an expression STATEMENT,
        // and Java discards an expression statement's value (JLS §14.8). A value
        // the language throws away cannot be a committed successor, so this is an
        // exact rule, not a heuristic: it belongs beside F9 and state enumeration
        // on the compiler-checked side of the line, not with the approximate
        // data-flow. Handing such statements to `handleValue` made every piece of
        // ordinary plumbing inside a walked body into a transition —
        // `Objects.requireNonNull(event, "...")`, `log.debug(...)`,
        // `metrics.increment()` — inflating the denominator with non-transitions
        // and, where the expression was hierarchy-typed, threatening a fabricated
        // resolved edge. A statement that genuinely IS the commit is already
        // claimed above by its own branch: an assignment to the state field, or a
        // recognised mutator call (F2). Nothing else installs a successor.
        //
        // Local-variable declarations and other plain statements likewise do not
        // directly produce a next state (reassigned locals are a scope line), so
        // they are intentionally not descended for value production.
    }

    /**
     * Is this expression the direct arm body of a switch <em>expression</em> —
     * i.e. is its value consumed rather than discarded? Answered from the node's
     * own position rather than a flag threaded through {@link #walk}, because it
     * is a property of where the expression sits, and a flag would have to be
     * passed correctly at ten call sites to say the same thing. A statement nested
     * inside an arm's block is NOT an arm body: its value is discarded like any
     * other statement, and the block's producer is its {@code yield}.
     */
    private static boolean isSwitchExpressionArm(CtExpression<?> expr) {
        try {
            CtElement parent = expr.getParent();
            return parent instanceof CtCase<?> c && c.getParent() instanceof CtSwitchExpression<?, ?>;
        } catch (Throwable t) {
            return false; // unknown position: assume statement, the safe direction
        }
    }

    /**
     * Walk the statements of a block left to right, threading a fall-through
     * guard. The idiom {@code if (cond) yield A; yield B;} has no {@code else},
     * yet {@code B} is reached only when {@code cond} is false: whenever an
     * {@code if} without an {@code else} definitely terminates its then-branch,
     * the negated condition guards every subsequent sibling. This recovers the
     * precise, mutually-exclusive guards real code relies on.
     */
    private void walkBlock(CtBlock<?> block, String from, String event,
                           String guard, Set<Transition> out) {
        String acc = guard;
        String accFrom = from;
        for (CtStatement st : block.getStatements()) {
            DispatchCommitDetector.TypeChain chain =
                    st instanceof CtIf ctIf ? typeChainAt(ctIf) : null;
            if (chain != null && chain.otherwise() == null && chainLinksAllTerminate(chain)) {
                // A closed type-test chain narrows what follows it. `if (s
                // instanceof Shut) {...} else if (s instanceof Ajar) {...}` whose
                // links all return leaves exactly the untested states reaching the
                // next statement, so a trailing `return s;` is a self-loop on
                // those — not the `<unknown>` origin a single from-state forces.
                // The same closed-world reasoning as the permits clause: the
                // selector is one of the permitted subtypes or none of them.
                walk(st, accFrom, event, acc, out);
                Map<String, String> residual = chainResidual(chain, candidateStates(accFrom));
                if (residual.isEmpty()) {
                    // Every state was tested and every branch left the method:
                    // nothing that follows is reachable in any state.
                    break;
                }
                if (residual.size() == 1 && residual.values().iterator().next() == null) {
                    accFrom = residual.keySet().iterator().next();
                    continue;
                }
                // Several states can still reach the rest of the block. Walking it
                // once per state emits the one edge each of them really has; a
                // single merged walk would have to call the origin unknown.
                walkResidual(block, block.getStatements().indexOf(st) + 1,
                        residual, event, acc, out);
                return;
            }
            walk(st, accFrom, event, acc, out);
            if (st instanceof CtIf ctIf
                    && ctIf.getElseStatement() == null
                    && alwaysTerminates(ctIf.getThenStatement())) {
                acc = merge(acc, negate(safeText(ctIf.getCondition())));
            } else if (alwaysTerminates(st)) {
                break; // remaining statements are unreachable
            }
        }
    }

    /** Walk a block's tail once per state the preceding chain left possible. */
    private void walkResidual(CtBlock<?> block, int firstIndex, Map<String, String> residual,
                              String event, String guard, Set<Transition> out) {
        List<CtStatement> tail = block.getStatements().subList(firstIndex, block.getStatements().size());
        for (Map.Entry<String, String> e : residual.entrySet()) {
            String stateGuard = merge(guard, e.getValue());
            String acc = stateGuard;
            for (CtStatement st : tail) {
                walk(st, e.getKey(), event, acc, out);
                if (st instanceof CtIf ctIf
                        && ctIf.getElseStatement() == null
                        && alwaysTerminates(ctIf.getThenStatement())) {
                    acc = merge(acc, negate(safeText(ctIf.getCondition())));
                } else if (alwaysTerminates(st)) {
                    break;
                }
            }
        }
    }

    // ---- instanceof-chain dispatch -------------------------------------------

    /**
     * The chain headed by this {@code if}, or {@code null} when it is not one.
     * Decomposition is the detector's, not a second copy of it: a recognizer and
     * an extractor that each decide for themselves what a chain is would
     * eventually disagree, and the disagreement would be an edge attributed to a
     * state the classifier never admitted. Answered only while a selector is in
     * scope — outside a dispatch there is no value whose type tests mean this.
     */
    private DispatchCommitDetector.TypeChain typeChainAt(CtIf ctIf) {
        if (selector == null) return null;
        DispatchCommitDetector.TypeChain chain =
                DispatchCommitDetector.chainOf(ctIf, hierarchyQualifiedNames, rootQualifiedName);
        return chain != null && isSelectorVariable(chain.selector()) ? chain : null;
    }

    /** True when the chain discriminates the variable this walk is dispatching on. */
    private boolean isSelectorVariable(CtVariable<?> v) {
        if (selector == null || v == null) return false;
        return v == selector || selector.getSimpleName().equals(v.getSimpleName());
    }

    /**
     * The states the selector could be on entry to a fragment: the one already
     * attributed, or — when none is — every concrete state, which is exact
     * because the permits clause is.
     */
    private Set<String> candidateStates(String from) {
        return from == null ? concreteStateSimpleNames : Set.of(from);
    }

    /**
     * Walk an {@code instanceof} chain as a dispatch: each link's branch under the
     * state it tests for, and the final {@code else} under each state no link
     * claimed.
     *
     * <p>The type test itself is never recorded as a guard — it is the arm label,
     * and repeating it as a predicate would say the edge is conditional when it is
     * unconditional in that state. Whatever else the link's condition tests still
     * counts, and is split into an event label and a guard by the same rule the
     * carrier walk uses, so {@code if (s instanceof Shut && e == OPEN)} yields
     * {@code Shut --OPEN--> ...} rather than one edge labelled with a predicate.
     */
    private void walkTypeChain(CtIf head, Set<String> candidates, String event,
                               String guard, Set<Transition> out) {
        DispatchCommitDetector.TypeChain chain = typeChainAt(head);
        if (chain == null) return;
        for (DispatchCommitDetector.ChainLink link : chain.links()) {
            String from = stateId(link.type());
            if (!candidates.contains(from)) {
                // Unreachable test: the selector provably is not this type here, so
                // a return inside the branch is proven dead rather than unread —
                // reporting it as a gap would invent one (F18).
                markUnreachable(link.branch());
                continue;
            }
            // The arm matched this state, so anything it fails to produce is an
            // absence the analysis observed rather than one it missed.
            dispatchedStates.add(from);
            EventCond ec = splitConjuncts(link.extra());
            String linkGuard = merge(guard, ec.residual());
            if (ec.symbols().isEmpty()) {
                walk(link.branch(), from, event, linkGuard, out);
            } else {
                for (String sym : ec.symbols()) {
                    walk(link.branch(), from, sym, linkGuard, out);
                }
            }
        }
        if (chain.otherwise() == null) return;
        // Not flagged as an `otherwise` edge, though it is the chain's default
        // branch: walkSwitch does not flag a `default` arm either, and a rule that
        // held for one spelling of the default and not the other would put the
        // difference between two idioms into the model. Extending the flag to the
        // centralized walk is one change covering both.
        for (Map.Entry<String, String> e : chainResidual(chain, candidates).entrySet()) {
            dispatchedStates.add(e.getKey());
            walk(chain.otherwise(), e.getKey(), event, merge(guard, e.getValue()), out);
        }
    }

    /**
     * Which states can still be current once every link's test has failed, and
     * under what guard.
     *
     * <p>A link testing {@code s instanceof T} with nothing else removes T
     * outright. A link testing {@code s instanceof T && extra} does not: its
     * branch is skipped whenever {@code extra} is false, so T is still reachable
     * below — under {@code !extra}. Dropping T there would delete a real edge;
     * keeping it without the negation would report it as unconditional.
     */
    private Map<String, String> chainResidual(DispatchCommitDetector.TypeChain chain,
                                              Set<String> candidates) {
        Map<String, String> residual = new LinkedHashMap<>();
        for (String c : candidates) residual.put(c, null);
        for (DispatchCommitDetector.ChainLink link : chain.links()) {
            String id = stateId(link.type());
            if (!residual.containsKey(id)) continue;
            if (link.extra().isEmpty()) {
                residual.remove(id);
            } else {
                // The negation is of the source condition, not of the Σ symbols it
                // split into: a symbol is an edge LABEL and is not an expression,
                // so negating it would put `!(LOWER)` in a DOT label and an SCXML
                // `cond` attribute, where a predicate is expected.
                String taken = null;
                for (CtExpression<?> c : link.extra()) taken = merge(taken, safeText(c));
                residual.put(id, merge(residual.get(id), negate(taken)));
            }
        }
        return residual;
    }

    /** Do all of a chain's link branches leave the method, so the chain has a residual? */
    private static boolean chainLinksAllTerminate(DispatchCommitDetector.TypeChain chain) {
        for (DispatchCommitDetector.ChainLink link : chain.links()) {
            if (link.branch() == null || !alwaysTerminates(link.branch())) return false;
        }
        return !chain.links().isEmpty();
    }

    /** Split a link's remaining conjuncts into Σ symbols and a residual data guard. */
    private EventCond splitConjuncts(List<CtExpression<?>> conjuncts) {
        Set<String> symbols = new LinkedHashSet<>();
        String residual = null;
        for (CtExpression<?> c : conjuncts) {
            EventCond ec = splitEventCondition(c);
            symbols.addAll(ec.symbols());
            residual = merge(residual, ec.residual());
        }
        return new EventCond(symbols, residual);
    }

    /**
     * Best-effort answer to JLS §14.22 — <em>can</em> this statement complete
     * normally? — inverted: does control leaving {@code st} never fall through
     * to the next statement? Two things ride on it: the negated condition of an
     * {@code if} with no {@code else} becomes the guard of every later sibling,
     * and a sibling after a statement that cannot complete normally is not
     * walked at all. Both directions of a wrong answer are damaging — a false
     * {@code true} either fabricates a guard the source does not impose or
     * writes off a live producer as unreachable, a false {@code false} emits a
     * guard weaker than the code — so the rule is exact where it fires and
     * answers {@code false} whenever it is unsure.
     *
     * <p>F14: {@code throw} was missing, and it is the common way an arm
     * rejects an input. {@code if (bad) throw ...; yield new A();} therefore
     * reported the producer as unconditional, which is a claim the source does
     * not make: guards reach the SCXML {@code cond} attribute and the
     * nondeterminism analysis, so the loss was silent.
     */
    private static boolean alwaysTerminates(CtStatement st) {
        if (st instanceof CtReturn<?> || st instanceof CtYieldStatement
                || st instanceof CtThrow) {
            return true;
        }
        // `break`/`continue` complete abruptly too: control leaves for the
        // enclosing switch's end or the loop's next iteration, so the following
        // sibling is not reached on this path either.
        if (st instanceof CtBreak || st instanceof CtContinue) {
            return true;
        }
        if (st instanceof CtBlock<?> block) {
            List<CtStatement> body = block.getStatements();
            return !body.isEmpty() && alwaysTerminates(body.get(body.size() - 1));
        }
        if (st instanceof CtIf ctIf) {
            return ctIf.getElseStatement() != null
                    && alwaysTerminates(ctIf.getThenStatement())
                    && alwaysTerminates(ctIf.getElseStatement());
        }
        if (st instanceof CtSwitch<?> sw) {
            return switchAlwaysTerminates(sw);
        }
        return false;
    }

    /**
     * A {@code switch} statement cannot complete normally only when <em>every</em>
     * way out of it is abrupt. Three conditions, and dropping any one of them
     * turns the rule into a fabrication:
     *
     * <ol>
     *   <li>it must be <b>exhaustive</b> — otherwise a selector matching no label
     *       falls straight through to the next statement;</li>
     *   <li>no {@code break} may target it — a {@code break} resumes control
     *       immediately <em>after</em> the switch, which is precisely the
     *       fall-through this predicate denies;</li>
     *   <li>every arm must itself terminate.</li>
     * </ol>
     *
     * <p>Condition 2 is answered by the presence of any {@code break} anywhere
     * beneath the switch, which over-counts a {@code break} belonging to a nested
     * loop. That is the conservative direction (the predicate declines rather
     * than claims) and it keeps the rule free of scope reasoning it would
     * otherwise have to get exactly right.
     */
    private static boolean switchAlwaysTerminates(CtSwitch<?> sw) {
        try {
            List<? extends CtCase<?>> cases = sw.getCases();
            if (cases.isEmpty() || !isExhaustive(cases)) return false;
            if (!sw.getElements(new TypeFilter<>(CtBreak.class)).isEmpty()) return false;
            for (int i = 0; i < cases.size(); i++) {
                if (!caseAlwaysTerminates(cases.get(i), i == cases.size() - 1)) return false;
            }
            return true;
        } catch (Throwable t) {
            return false; // unknown shape: assume it falls through, the safe direction
        }
    }

    /**
     * Exhaustive by a rule the compiler has already checked, never by counting
     * labels against a type: a {@code default} arm covers everything by
     * definition, and a pattern label makes this an <em>enhanced</em> switch
     * statement, which JLS §14.11.2 requires to be exhaustive. An enum switch
     * with no {@code default} is a legacy switch and is not required to cover
     * its constants, so it answers {@code false} even when it happens to.
     */
    private static boolean isExhaustive(List<? extends CtCase<?>> cases) {
        for (CtCase<?> c : cases) {
            List<? extends CtExpression<?>> labels = c.getCaseExpressions();
            if (labels == null || labels.isEmpty()) return true; // `default`
            for (CtExpression<?> label : labels) {
                if (isPatternLabel(label)) return true;
            }
        }
        return false;
    }

    /**
     * Is this case label a pattern rather than a constant? Spoon has spelled it
     * both ways — a bare {@code CtTypePattern} in 10.x, a {@code CtCasePattern}
     * wrapping one since 11 — so the newer form is probed reflectively, in the
     * style of {@link #patternType}. It must not answer yes for an enum constant:
     * a constant read carries its enum's type, and a rule keyed on "the label has
     * a type" would call every enum switch exhaustive.
     */
    private static boolean isPatternLabel(CtExpression<?> label) {
        return label instanceof CtTypePattern || tryMethod(label, "getPattern") != null;
    }

    /**
     * Does this arm complete abruptly? An arm with no statements is a colon-style
     * label falling into the group below, which answers for both — unless it is
     * the LAST group, where the fall-through leaves the switch: {@code switch (x)
     * { default: }} completes normally precisely because nothing follows it.
     */
    private static boolean caseAlwaysTerminates(CtCase<?> c, boolean lastGroup) {
        List<CtStatement> body = c.getStatements();
        if (body.isEmpty()) return !lastGroup;
        CtStatement last = body.get(body.size() - 1);
        // F10 — Spoon wraps the arrow arm of a switch STATEMENT in a synthetic
        // CtYieldStatement, though `yield` is illegal outside a switch
        // expression. It stands for an expression whose value is discarded, and
        // an expression statement completes normally. A genuine `yield` is
        // always nested inside an arm's own block, never a direct child of the
        // case, so this one-level test cannot mistake the two.
        if (last instanceof CtYieldStatement) return false;
        return alwaysTerminates(last);
    }

    /** Resolve one produced expression, descending into a nested switch first. */
    private void handleValue(CtExpression<?> value, String from, String event,
                             String guard, Set<Transition> out) {
        if (value == null) return;
        if (value instanceof CtSwitchExpression<?, ?> sw) {
            walkSwitch(sw, from, event, guard, out);
            return;
        }
        // F1: a read of a *reassigned local* must not be resolved from its
        // declared type — a root-typed local (e.g. `Door next = current;`) would
        // otherwise yield a false, guardless self-loop while the real target of an
        // intervening `next = new Locked();` is lost. Recover the local's reaching
        // definitions (each under its own path guard) instead.
        if (value instanceof CtVariableAccess<?> va && isReassignedLocal(va)) {
            handleReassignedLocal(va, from, event, guard, out);
            return;
        }
        // F3: an invocation returning the hierarchy type may be an in-model
        // helper/factory; fold its bounded return-value summary when we soundly
        // can, otherwise fall through and record it unresolved as before.
        if (value instanceof CtInvocation<?> inv
                && resolveInterprocedural(inv, from, event, guard, out)) {
            return;
        }
        // CARRIER_RETURN: this dispatch's arms hand the successor to a wrapper, so
        // the produced value IS the wrapper and must be unwrapped before it can be
        // resolved. The unwrapping is handleCarrierValue's, unchanged and shared —
        // one level into the arguments, F9 applied at the call, a recorded gap when
        // the successor is computed elsewhere. Sharing it rather than restating it
        // is what makes this a new (locus, commit) cell rather than a second
        // carrier implementation free to drift from the first.
        //
        // BELOW the fold attempt, and the order is load-bearing. `case Initial i ->
        // fromInitial(event)` is a call whose type is the carrier, so unwrapping
        // first finds no hierarchy-typed argument in `(event)` and records a gap —
        // for every arm, on a machine whose relation is entirely in its helpers.
        // Folding first lets the helper's own returns arrive here as the carrier
        // constructions they are, and they unwrap.
        //
        // Inert on the POLYMORPHIC_OVERRIDE carrier path: there handleCarrierValue
        // is already the walker and only calls back here with a value it has
        // ALREADY unwrapped, which isCarrierStateValue answers true for.
        if (commitsThroughCarrier() && !isCarrierStateValue(value)) {
            handleCarrierValue(value, from, event, guard, out);
            return;
        }
        // F20: this value COMPOSES a hierarchy value — it builds a node around
        // another one rather than naming a peer of the current state. The
        // compositional veto is bounded now, so such an expression can survive
        // into an accepted machine; it must not then be published as a resolved
        // successor, because the constructed node's relationship to the current
        // state is containment, not succession. The alternative to recording it
        // here is the old behaviour, where one such expression deleted the whole
        // hierarchy. Carrying the current state is NOT this case: `new
        // Retrying(this, n + 1)` is a successor that remembers its predecessor,
        // and the predicate excludes it.
        if (CarrierTransitionDetector.nestsHierarchyValue(value, hierarchyQualifiedNames)) {
            nestedProductions++;
            out.add(mark(Transition.unresolved(from == null ? "<unknown>" : from, event, guard,
                    safeText(value) + " [composes a hierarchy value; not a successor]"), event));
            return;
        }
        for (TransitionResolver.Candidate cand : resolver.resolve(value, from)) {
            emit(from, event, merge(guard, cand.guard()), cand, out);
        }
    }

    // ---- F1: reaching-definitions for reassigned locals ----------------------

    /** A candidate value of a local, with the path guard under which it is live. */
    private record Def(CtExpression<?> rhs, String guard) {}

    /**
     * True when {@code va} reads a <em>local</em> variable that is assigned
     * somewhere in its method. Such a read is intercepted before the resolver so
     * its declared type never fabricates a self-loop; a non-reassigned local (a
     * pattern binding, a single-init local) keeps the ordinary resolver path.
     *
     * <p>"That variable", not "that name" — the write has to be bound to this
     * declaration (F13). Sibling switch arms routinely each declare their own
     * {@code next}, and a name-keyed scan puts every one of them on the
     * reaching-definitions path as soon as any one of them is an accumulator.
     */
    private boolean isReassignedLocal(CtVariableAccess<?> va) {
        CtVariableReference<?> vref = va.getVariable();
        if (vref == null || !(vref.getDeclaration() instanceof CtLocalVariable<?>)) {
            return false;
        }
        return TransitionResolver.isReassigned(vref, nameOnlyReassignmentChecks::add);
    }

    /**
     * Resolve a reassigned local by intra-procedural, flow-sensitive reaching
     * definitions over its declaring block. Each reaching right-hand side is fed
     * back through {@link #handleValue} so constructor calls, self-loops
     * ({@code current}), ternaries — and unresolvable helpers — are all handled
     * uniformly. If the structured analysis cannot model the control flow, the
     * whole read is emitted as one unresolved edge rather than guessed.
     */
    private void handleReassignedLocal(CtVariableAccess<?> va, String from, String event,
                                       String guard, Set<Transition> out) {
        CtVariableReference<?> vref = va.getVariable();
        CtVariable<?> decl = vref == null ? null : vref.getDeclaration();
        CtBlock<?> scope = decl == null ? null : decl.getParent(CtBlock.class);
        List<Def> defs = scope == null ? null : reachingDefs(scope, va, decl, new ArrayList<>());
        if (defs == null || defs.isEmpty()) {
            out.add(Transition.unresolved(from == null ? "<unknown>" : from, event, guard, safeText(va)));
            return;
        }
        for (Def d : defs) {
            handleValue(d.rhs(), from, event, merge(guard, d.guard()), out);
        }
    }

    /**
     * Reaching definitions of {@code decl} at the point of {@code use}, threading
     * the incoming set {@code in} through the structured control flow of the
     * declaring block. Returns {@code null} — meaning "cannot prove" — when a
     * construct outside the modelled subset (loops, try, nested switch) encloses
     * the use.
     */
    private List<Def> reachingDefs(CtElement scope, CtVariableAccess<?> use,
                                   CtVariable<?> decl, List<Def> in) {
        if (scope instanceof CtReturn<?> || scope instanceof CtYieldStatement
                || scope instanceof CtExpression<?>) {
            // the use is the returned / yielded / arrow expression: value == `in`
            return in;
        }
        if (scope instanceof CtBlock<?> block) {
            List<Def> cur = in;
            for (CtStatement st : block.getStatements()) {
                if (contains(st, use)) {
                    return reachingDefs(st, use, decl, cur);
                }
                cur = transfer(st, cur, decl);
                if (cur == null) return null;
            }
            return cur;
        }
        if (scope instanceof CtIf ctIf) {
            if (contains(ctIf.getThenStatement(), use)) {
                return reachingDefs(ctIf.getThenStatement(), use, decl, in);
            }
            if (contains(ctIf.getElseStatement(), use)) {
                return reachingDefs(ctIf.getElseStatement(), use, decl, in);
            }
            return null; // use sits in the condition — not modelled
        }
        return null; // loop / try / switch enclosing the use — bail
    }

    /**
     * Transfer function for one statement: the reaching set after {@code st}
     * executes, given the set {@code in} before it. An unconditional write kills
     * all prior definitions; an {@code if} joins its branches, conjoining each
     * side's guard with the (negated) condition. Returns {@code null} when the
     * variable is written inside an unmodelled construct.
     */
    private List<Def> transfer(CtStatement st, List<Def> in, CtVariable<?> decl) {
        String name = decl.getSimpleName();
        if (st instanceof CtLocalVariable<?> lv) {
            if (name.equals(lv.getSimpleName())) {
                List<Def> out = new ArrayList<>();
                CtExpression<?> init = lv.getDefaultExpression();
                if (init != null) out.add(new Def(init, null));
                return out; // declaration (re)binds the name
            }
            return in;
        }
        if (st instanceof CtAssignment<?, ?> asg) {
            if (writesTo(asg.getAssigned(), name)) {
                List<Def> out = new ArrayList<>();
                out.add(new Def(asg.getAssignment(), null)); // kills all prior on this path
                return out;
            }
            return in;
        }
        if (st instanceof CtBlock<?> block) {
            List<Def> cur = in;
            for (CtStatement s : block.getStatements()) {
                cur = transfer(s, cur, decl);
                if (cur == null) return null;
            }
            return cur;
        }
        if (st instanceof CtIf ctIf) {
            boolean thenWrites = writesSomewhere(ctIf.getThenStatement(), name);
            boolean elseWrites = writesSomewhere(ctIf.getElseStatement(), name);
            if (!thenWrites && !elseWrites) return in; // neither branch touches it
            List<Def> outThen = transferBranch(ctIf.getThenStatement(), in, decl);
            List<Def> outElse = transferBranch(ctIf.getElseStatement(), in, decl);
            if (outThen == null || outElse == null) return null;
            String c = safeText(ctIf.getCondition());
            List<Def> merged = new ArrayList<>();
            for (Def d : outThen) merged.add(new Def(d.rhs(), merge(c, d.guard())));
            for (Def d : outElse) merged.add(new Def(d.rhs(), merge(negate(c), d.guard())));
            return merged;
        }
        // Unmodelled statement: safe to skip only if it leaves the variable alone.
        return writesSomewhere(st, name) ? null : in;
    }

    private List<Def> transferBranch(CtStatement branch, List<Def> in, CtVariable<?> decl) {
        return branch == null ? in : transfer(branch, in, decl);
    }

    /** Does {@code lhs} write the local named {@code name} (and not a field of that name)? */
    private static boolean writesTo(CtExpression<?> lhs, String name) {
        return lhs instanceof CtVariableAccess<?> va
                && !(lhs instanceof CtFieldAccess<?>)
                && va.getVariable() != null
                && name.equals(va.getVariable().getSimpleName());
    }

    /** Is the local named {@code name} assigned anywhere within {@code stmt}? */
    private static boolean writesSomewhere(CtStatement stmt, String name) {
        if (stmt == null) return false;
        for (CtAssignment<?, ?> a : stmt.getElements(new TypeFilter<>(CtAssignment.class))) {
            if (writesTo(a.getAssigned(), name)) return true;
        }
        return false;
    }

    /** Is {@code node} the same element as, or a descendant of, {@code ancestor}? */
    private static boolean contains(CtElement ancestor, CtElement node) {
        if (ancestor == null || node == null) return false;
        CtElement cur = node;
        while (cur != null) {
            if (cur == ancestor) return true;
            if (!cur.isParentInitialized()) return false;
            cur = cur.getParent();
        }
        return false;
    }

    /**
     * Walk the arms of a switch. When the switch dispatches on the state type,
     * each arm's from-state is the matched type pattern. When it dispatches on the
     * event type (finding F4), each arm's event label is the matched event —
     * mirroring the state case exactly. Otherwise both are inherited.
     *
     * <p>A multi-label arm ({@code case SEND_RST_STREAM, RECV_RST_STREAM -> ...})
     * is walked once <em>per label</em>. Those are distinct inputs of Σ that happen
     * to share a body, so collapsing them into one edge would silently drop every
     * label but the first — the same reasoning that makes a disjunction of event
     * tests several edges in the carrier walk.
     *
     * <p>A third dispatch kind appears one level further in: when an event arm
     * deconstructs its event ({@code case Send(Signal s) ->}) and its body switches
     * on the binding, that inner switch selects the <em>component</em>, and the two
     * levels together name one input. Its arms are labelled with the composed
     * symbol {@code Send.HEADERS} rather than with the bare family {@code Send},
     * which is what lets an event modelled as a record-plus-enum be compared with
     * the same event modelled as a flat constant.
     */
    private void walkSwitch(CtAbstractSwitch<?> sw, String from, String event,
                            String guard, Set<Transition> out) {
        boolean overState = isStateDispatch(sw);
        boolean overEvent = !overState && isEventDispatch(sw);
        boolean overComponent = !overState && !overEvent && isComponentDispatch(sw);
        // F12 — arm order is part of the semantics. A later arm carrying the same
        // label as an earlier GUARDED one is reached only when that guard was
        // false, so the two are mutually exclusive by construction. Without the
        // negation, `case Timeout t when t.counter() > 0 -> ...` followed by
        // `case Timeout t -> ...` is reported as [g] against [true], which the
        // overlap check reads as nondeterminism the source does not contain: 14
        // such warnings on examples/lcp_automation for a deterministic RFC 1661
        // automaton. This is the same reasoning walkBlock already applies to an
        // `if` with no `else`, carried across sibling arms.
        Map<String, List<String>> guardsByLabel = new LinkedHashMap<>();
        // F18 — a state switch inside a body walked with the from-state ALREADY
        // known re-discriminates a value whose type the caller has proven. Folding
        // `escalate(current)` from `case Idle i ->` must not reopen that question:
        // the arms for other states cannot run here, and a `default` arm covers
        // only what is left over. Without this, folding any state-switching helper
        // emits a second edge sourced at `<unknown>` — an origin invented out of a
        // context the walk already had. `remaining` stays null, and nothing is
        // filtered, whenever the from-state is not yet known, which is every
        // top-level dispatch: the host walk is untouched.
        Set<String> remaining = overState && from != null
                ? new LinkedHashSet<>(candidateStates(from))
                : null;
        for (CtCase<?> c : sw.getCases()) {
            String caseFrom = from;
            String ownGuard = caseGuard(c);
            if (overState) {
                caseFrom = caseFromState(c);
                if (remaining != null) {
                    if (caseFrom == null) {
                        // An unlabelled `default` fires for the states no earlier arm
                        // claimed — for none of them once they all have.
                        if (remaining.isEmpty()) {
                            markUnreachable(c);
                            continue;
                        }
                        caseFrom = remaining.iterator().next();
                    } else if (!remaining.contains(caseFrom)) {
                        markUnreachable(c);
                        continue;
                    }
                    // Only an UNGUARDED arm consumes its state: a guarded one may
                    // not fire, so the state stays live for the arms below it. Same
                    // reasoning `chainResidual` applies to a link carrying an extra
                    // condition.
                    if (ownGuard == null) remaining.remove(caseFrom);
                }
                if (caseFrom == null) {
                    diagnostics.add(unresolvedArmDiagnostic(c));
                    // fall through with a null from so produced targets are still
                    // recorded (as undetermined-origin) rather than dropped.
                } else {
                    // The arm matched this state, so anything it fails to produce is
                    // an absence the analysis observed rather than one it missed.
                    dispatchedStates.add(caseFrom);
                }
            }
            List<String> caseEvents = overEvent ? caseEventNames(c)
                    : overComponent ? componentEventNames(c)
                    : List.<String>of();
            if (caseEvents.isEmpty()) {
                // Not an event dispatch, or an unlabelled `default` arm: the
                // inherited event label carries through unchanged. Inside a
                // component switch that inherited label is the bare family name
                // (`Send`), which is the honest reading — the arm fires for every
                // component value the labelled arms did not name.
                caseEvents = Collections.singletonList(event); // may hold null
            }
            // Labels are the discriminator the exclusion is keyed on: only arms
            // that can match the SAME input compete. Distinct labels are already
            // disjoint, and negating those would bury every edge under a pile of
            // redundant `!(event instanceof X)` clauses.
            List<String> labels = overState ? Collections.singletonList(caseFrom) : caseEvents;
            String caseGuard = merge(merge(guard, priorExclusion(labels, guardsByLabel)), ownGuard);
            // Only a GUARDED arm constrains its successors. An unguarded arm
            // dominates every later arm with the same label, which the compiler
            // already rejects, so there is nothing to record for it.
            if (ownGuard != null) {
                for (String label : labels) {
                    guardsByLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(ownGuard);
                }
            }
            // If this arm deconstructed its event, remember the binding so a switch
            // on it inside the arm body refines the label instead of inheriting it.
            ComponentEvent binding = overEvent && caseEvents.size() == 1
                    ? componentBindingOf(c, caseEvents.get(0)) : null;
            ComponentEvent savedComponent = componentEvent;
            if (binding != null) componentEvent = binding;
            // F10 — Spoon wraps the arrow arm of a switch STATEMENT in a synthetic
            // CtYieldStatement (`case X -> ctx.setState(new A());` arrives as a
            // yield), even though `yield` is illegal outside a switch expression.
            // Walking it as a yield reads a discarded value as a produced one. Any
            // yield directly under a CtSwitch is therefore synthetic and must be
            // unwrapped to the statement it really is — which also routes it back
            // through `walk`, where the F2 mutator branch can claim it. Inside an
            // arm's BLOCK a yield is always genuine, so this is a one-level rule.
            boolean discardedArms = !(sw instanceof CtSwitchExpression<?, ?>);
            try {
                CtElement leaked = leakedGuard(c);
                for (String caseEvent : caseEvents) {
                    for (CtStatement st : c.getStatements()) {
                        // The leaked `when` clause is the arm's guard, consumed
                        // above; it is not part of the arm's body.
                        if (st == leaked) continue;
                        CtElement effective = st;
                        if (discardedArms && st instanceof CtYieldStatement ys
                                && ys.getExpression() != null) {
                            effective = ys.getExpression();
                        }
                        walk(effective, caseFrom, caseEvent, caseGuard, out);
                    }
                }
            } finally {
                componentEvent = savedComponent;
            }
        }
    }

    /**
     * Is this switch selecting the component bound by the enclosing event arm?
     * Matched on the binding's NAME and its enum TYPE together: a record-pattern
     * binding has no resolvable declaration in the Spoon model (verified — the
     * inner selector's {@code getDeclaration()} is {@code null}), so identity
     * comparison is unavailable, and the name alone would claim an unrelated
     * switch that happened to reuse a one-letter variable.
     */
    private boolean isComponentDispatch(CtAbstractSwitch<?> sw) {
        ComponentEvent ctx = componentEvent;
        if (ctx == null) return false;
        CtExpression<?> sel;
        try {
            sel = sw.getSelector();
        } catch (Throwable t) {
            return false;
        }
        if (!(sel instanceof CtVariableAccess<?> va) || va.getVariable() == null) return false;
        if (!ctx.boundName().equals(va.getVariable().getSimpleName())) return false;
        CtTypeReference<?> t = sel.getType();
        return t != null && ctx.enumQualifiedName().equals(t.getQualifiedName());
    }

    /**
     * The binding a record-pattern event arm introduced, or {@code null} when the
     * arm binds no single enum component. Requiring exactly one keeps this in step
     * with {@link #soleEnumComponent}: where Σ declined to expand, the label
     * declines to compose, so the two can never describe different alphabets.
     */
    private ComponentEvent componentBindingOf(CtCase<?> c, String prefix) {
        if (prefix == null) return null;
        try {
            for (CtExpression<?> ce : c.getCaseExpressions()) {
                Object pattern = tryMethod(ce, "getPattern");
                if (pattern == null) continue;
                if (!(tryMethod(pattern, "getPatternList") instanceof List<?> subs)) continue;
                ComponentEvent found = null;
                for (Object sub : subs) {
                    Object variable = tryMethod(sub, "getVariable");
                    if (variable == null) continue;
                    if (!(tryMethod(variable, "getSimpleName") instanceof String name)) continue;
                    if (!(tryMethod(variable, "getType") instanceof CtTypeReference<?> t)) continue;
                    if (!(t.getTypeDeclaration() instanceof CtEnum<?> en)
                            || en.getEnumValues().isEmpty()) {
                        continue;
                    }
                    if (found != null) return null; // several enum components — ambiguous
                    found = new ComponentEvent(prefix, name, t.getQualifiedName());
                }
                if (found != null) return found;
            }
        } catch (Throwable ignored) {
            // best effort: no binding simply means the arm keeps its family label
        }
        return null;
    }

    /**
     * The Σ symbols an arm of a component switch matches: the enclosing event
     * prefix joined to each matched constant. A composed symbol is emitted only if
     * Σ already contains it — Σ is enumerated from the types and is the authority,
     * so a label can never name an input the alphabet does not have. When it does
     * not, the arm falls back to inheriting the bare family label.
     */
    private List<String> componentEventNames(CtCase<?> c) {
        List<String> out = new ArrayList<>();
        ComponentEvent ctx = componentEvent;
        if (ctx == null) return out;
        try {
            for (CtExpression<?> ce : c.getCaseExpressions()) {
                if (!(ce instanceof CtFieldAccess<?> fa) || fa.getVariable() == null) continue;
                String composed = composedSymbol(ctx.prefix(), fa.getVariable().getSimpleName());
                if (alphabet.contains(composed)) out.add(composed);
            }
        } catch (Throwable ignored) {
            // best effort — an unreadable label keeps the inherited event
        }
        return out;
    }

    private void emit(String from, String event, String guard,
                      TransitionResolver.Candidate cand, Set<Transition> out) {
        if (cand.resolved()) {
            if (from == null) {
                out.add(mark(Transition.unresolved("<entry>", event, guard,
                        "target " + cand.targetSimpleName() + " with undetermined source state"), event));
            } else {
                out.add(mark(Transition.resolved(from, cand.targetSimpleName(), event, guard)
                        .withForm(cand.form()), event));
            }
        } else {
            out.add(mark(Transition.unresolved(from == null ? "<unknown>" : from,
                    event, guard, cand.raw()), event));
        }
    }

    /**
     * Flag an edge reached through an {@code else}, a {@code default} arm or a
     * fall-through as the state's default ("otherwise") transition — but only when
     * no event selected it. An else branch under an event test still fires on that
     * event and is not a default edge.
     */
    private Transition mark(Transition t, String event) {
        return otherwisePath && event == null ? t.asOtherwise() : t;
    }

    // ---- Spoon-version-sensitive accessors (all guarded) ---------------------

    /**
     * Does this method discriminate the state anywhere — by a switch over the
     * hierarchy, or by a chain of type tests on the selector? Both are dispatch;
     * only asking about the switch made a perfectly well attributed chain report
     * the "from-states could not be attributed" diagnostic.
     */
    private boolean containsStateDispatch(CtMethod<?> method) {
        for (CtSwitch<?> sw : method.getElements(new TypeFilter<>(CtSwitch.class))) {
            if (isStateDispatch(sw)) return true;
        }
        for (CtSwitchExpression<?, ?> sw : method.getElements(new TypeFilter<>(CtSwitchExpression.class))) {
            if (isStateDispatch(sw)) return true;
        }
        for (CtIf ctIf : method.getElements(new TypeFilter<>(CtIf.class))) {
            if (typeChainAt(ctIf) != null) return true;
        }
        return false;
    }

    private boolean isStateDispatch(CtAbstractSwitch<?> sw) {
        CtTypeReference<?> selType = selectorType(sw);
        return selType != null && hierarchyQualifiedNames.contains(selType.getQualifiedName());
    }

    /** True when the switch selector is the event type (F4). */
    private boolean isEventDispatch(CtAbstractSwitch<?> sw) {
        CtTypeReference<?> selType = selectorType(sw);
        return selType != null && eventQualifiedNames.contains(selType.getQualifiedName());
    }

    private CtTypeReference<?> selectorType(CtAbstractSwitch<?> sw) {
        try {
            CtExpression<?> selector = sw.getSelector();
            return selector == null ? null : selector.getType();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Extract the matched type from a (possibly guarded) type-pattern case. */
    private String caseFromState(CtCase<?> c) {
        try {
            for (CtExpression<?> ce : c.getCaseExpressions()) {
                CtTypeReference<?> t = patternType(ce);
                if (t != null && hierarchyQualifiedNames.contains(t.getQualifiedName())) {
                    return stateId(t);
                }
            }
        } catch (Throwable ignored) {
            // fall through to null -> diagnostic
        }
        return null;
    }

    /**
     * Why this arm's from-state could not be determined — an unrecognised arm, or
     * an arm whose pattern type Spoon never resolved.
     *
     * <p>The two are the same event to {@link #caseFromState}, which answers null
     * for both, and the difference is invisible downstream: membership is decided
     * by qualified name against a set built from resolved declarations, so
     * {@code case Shut s} whose {@code Shut} did not resolve reads exactly like a
     * pattern naming some foreign type. Reporting both as "could not determine
     * source state" blamed the switch-arm handling for what is an input problem —
     * a machine with its state files missing from {@code --src} produced this
     * diagnostic once per arm and a 0/n score, and nothing said the types had not
     * been read. In a thesis reporting recall stratified by idiom that is a
     * misattributed recall figure, not merely a confusing message.
     *
     * <p>Nothing changes about the walk: the arm still contributes an edge with an
     * undetermined origin, exactly as before. Only the attribution changes.
     */
    private String unresolvedArmDiagnostic(CtCase<?> c) {
        CtTypeReference<?> pattern = casePatternType(c);
        if (pattern != null && SpoonCompat.isUnresolved(pattern)) {
            return "could not determine source state for a switch case because its pattern "
                    + "type '" + SpoonCompat.resolutionName(pattern) + "' did not resolve "
                    + "— a type-resolution failure, not an unrecognised arm: "
                    + truncate(safeText(c));
        }
        return "could not determine source state for a switch case: " + truncate(safeText(c));
    }

    /**
     * The first type named by a pattern label of {@code c}, whether or not it is a
     * hierarchy member. {@link #caseFromState} deliberately returns only members;
     * this returns what was written, which is what lets a failure be attributed.
     */
    private static CtTypeReference<?> casePatternType(CtCase<?> c) {
        return CasePatterns.patternTypeOf(c);
    }

    /**
     * Pull a type out of a case label that is a type pattern.
     *
     * <p>The reflective reading itself lives in {@link CasePatterns}, shared with
     * the dispatch finder: the recognizer that decides which state an arm matches
     * and the walker that later attributes an edge to it must not be able to
     * disagree about what a pattern label says.
     */
    private static CtTypeReference<?> patternType(Object caseExpr) {
        return CasePatterns.patternType(caseExpr);
    }

    /**
     * Best-effort reflective call, for the several other Spoon accessors whose
     * names have shifted across versions. {@link CasePatterns} owns its own copy
     * for the pattern-label reading it does; this one serves the loop, catch and
     * resource accessors below.
     */
    private static Object tryMethod(Object target, String name) {
        if (target == null) return null;
        try {
            var m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * The events matched by a switch-over-event arm (F4), in label order: a type
     * pattern for a sealed event ({@code case Lock l ->}) or a constant for an
     * enum event ({@code case SEND_HEADERS ->}). Returns an empty list for a
     * {@code default} arm, so the inherited event label is kept.
     *
     * <p>The enum-constant case is checked <em>first</em>, and the order is
     * load-bearing. A constant read carries the enum's own type, so asking
     * {@link #patternType} first answers "the event type" — labelling every arm of
     * {@code switch (event)} with the name of the enum instead of the constant it
     * matched, and collapsing the whole alphabet to a single symbol.
     */
    private List<String> caseEventNames(CtCase<?> c) {
        List<String> out = new ArrayList<>();
        try {
            for (CtExpression<?> ce : c.getCaseExpressions()) {
                String constant = enumConstantSymbol(ce);
                if (constant != null) {
                    out.add(constant);
                    continue;
                }
                // A constant outside Σ (or unresolvable under noClasspath) still
                // names itself; the bare simple name is what the source says.
                if (ce instanceof CtFieldAccess<?> fa && fa.getVariable() != null) {
                    out.add(fa.getVariable().getSimpleName());
                    continue;
                }
                CtTypeReference<?> t = patternType(ce);
                if (t != null && eventQualifiedNames.contains(t.getQualifiedName())) {
                    out.add(t.getSimpleName());
                    continue;
                }
                if (ce instanceof CtVariableAccess<?> va && va.getVariable() != null) {
                    out.add(va.getVariable().getSimpleName());
                }
            }
        } catch (Throwable ignored) {
            // best effort — an unreadable label keeps the inherited event
        }
        return out;
    }

    /**
     * Guarded-pattern {@code when} clause. Spoon 10.4.2 populates
     * {@code getGuard()} only when the guard is a {@code CtBinaryOperator}
     * ({@code when t.counter() > 0}); for any other shape — a bare invocation
     * ({@code when r.acceptable()}), a unary ({@code when !r.catastrophic()}), a
     * parenthesised invocation — the slot is left null and the guard expression is
     * instead PREPENDED INTO THE ARM BODY as a statement. Verified empirically
     * against all five shapes. So the guard is recovered from either place.
     */
    private String caseGuard(CtCase<?> c) {
        Object guard = tryMethod(c, "getGuard");
        if (guard != null) return safeText(guard);
        CtElement leaked = leakedGuard(c);
        return leaked == null ? null : safeText(leaked);
    }

    /**
     * The {@code when} clause Spoon leaked into the arm body, or {@code null}.
     *
     * <p>The discriminator is the ARROW case kind, and it is exact rather than a
     * guess: JLS §14.11.1 gives an arrow arm a single expression, block or
     * {@code throw}, so an arrow arm can never legitimately hold two statements.
     * A leading extra one is therefore always the leaked guard. The restriction
     * matters — a COLON arm holds a statement list, and {@code case X: helper();
     * return 1;} (verified) puts an ordinary boolean-returning call exactly where
     * the naive "first boolean statement is the guard" rule would misread it as a
     * condition and silently drop the call.
     */
    private CtElement leakedGuard(CtCase<?> c) {
        try {
            if (!"ARROW".equals(String.valueOf(tryMethod(c, "getCaseKind")))) return null;
            List<CtStatement> body = c.getStatements();
            if (body.size() < 2) return null;
            CtStatement first = body.get(0);
            if (!(first instanceof CtExpression<?> expr)) return null;
            // The ARROW + extra-statement structure is what PROVES this is the
            // guard; the type is only a sanity check against that reasoning being
            // wrong. So an unresolvable type (routine under noClasspath) is
            // accepted, and both the primitive and the boxed spelling count — a
            // `Boolean`-returning accessor is an ordinary way to write a guard and
            // was silently losing its condition.
            // A CAST is not its own node in Spoon — it hangs off the expression, so
            // `(Boolean) v.o()` reports the invocation's own type (Object) and was
            // rejected, losing the guard. The outermost cast is what the `when`
            // clause actually evaluates.
            CtTypeReference<?> t = expr.getType();
            List<CtTypeReference<?>> casts = expr.getTypeCasts();
            if (casts != null && !casts.isEmpty()) t = casts.get(casts.size() - 1);
            if (t == null) return first;
            String name = t.getSimpleName();
            return "boolean".equals(name) || "Boolean".equals(name) ? first : null;
        } catch (Throwable ignored) {
            return null; // unreadable: keep the previous (guardless) behaviour
        }
    }

    /**
     * The conjunction of the negated guards of earlier arms carrying any of
     * {@code labels} — the condition under which control actually reaches this arm.
     */
    private String priorExclusion(List<String> labels, Map<String, List<String>> guardsByLabel) {
        String exclusion = null;
        for (String label : labels) {
            for (String prior : guardsByLabel.getOrDefault(label, List.of())) {
                exclusion = merge(exclusion, negate(prior));
            }
        }
        return exclusion;
    }

    /**
     * Synthetic guard for a catch-block producer (F6): {@code "exception"}, with
     * the caught type in parentheses when it can be read. Attribution of the
     * catch is best-effort and never throws.
     */
    private String catchGuard(CtCatch cc) {
        Object param = tryMethod(cc, "getParameter");
        Object type = param == null ? null : tryMethod(param, "getType");
        if (type instanceof CtTypeReference<?> ref) {
            return "exception (" + ref.getSimpleName() + ")";
        }
        return "exception";
    }

    /**
     * The entry condition of a loop (F6), used as the guard on producers inside
     * the body. {@code while}/{@code do} expose {@code getLoopingExpression};
     * {@code for} exposes {@code getExpression}; {@code for-each} has neither and
     * yields a {@code null} (unconditional) guard.
     */
    private String loopGuard(CtLoop loop) {
        Object cond = tryMethod(loop, "getLoopingExpression");
        if (cond == null) cond = tryMethod(loop, "getExpression");
        return cond == null ? null : safeText(cond);
    }

    // ---- misc -----------------------------------------------------------------

    /**
     * The Σ symbol a transition method's NAME contributes, or {@code null} when it
     * contributes none.
     *
     * <p><b>F22 — a method name is an input symbol only when it DISCRIMINATES.</b>
     * This was a hard-coded list of English words ({@code next}, {@code transition},
     * {@code step}, {@code advance}, {@code tick}) held to be "neutral", which is
     * a claim about vocabulary rather than about the program, and it failed in
     * both directions on the corpus. {@code retrystate.Attempt} spells its one
     * transition {@code on(Signal)} and {@code retrystate.Frame} spells its one
     * {@code wrap(Frame)}: neither word was on the list, so every edge of both
     * machines was labelled with the function's own name while the real input —
     * {@code Signal.START} and friends — sat in the guard. Adding two more words
     * to the list would have moved the failure rather than removed it.
     *
     * <p>What separates the two cases is structural and already in the model. A
     * hierarchy whose states all override <em>one</em> method has named the
     * transition function; the choice of which transition to take is made by that
     * method's argument, so the name is a constant across every edge and carries
     * no information. A hierarchy whose states expose <em>several</em> — the GoF
     * spelling, {@code coin()} beside {@code push()} — makes the call site's choice
     * of method the input, and each name is then a genuine Σ symbol. The test is
     * therefore whether the names discriminate, decided per hierarchy in
     * {@link #extract} and reported as a diagnostic; no vocabulary appears here.
     */
    private static String eventName(CtMethod<?> method, boolean namesDiscriminate) {
        return namesDiscriminate ? method.getSimpleName() : null;
    }

    private static String merge(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + " && " + b;
    }

    private static String negate(String condText) {
        if (condText == null || condText.isBlank()) return null;
        return "!(" + condText + ")";
    }

    /**
     * Source text of a node, flattened to one line. Guard text reaches a DOT edge
     * label and an SCXML {@code cond} attribute, and a guard can legitimately span
     * lines — {@code when switch (v.n()) { case 1 -> true; default -> false; }}
     * pretty-prints across five. Graphviz accepts the embedded newlines, so this
     * failed silently rather than loudly: the diagram just grew an unreadable
     * five-line label. Runs of whitespace collapse to one space.
     */
    private static String safeText(Object e) {
        if (e == null) return "";
        try {
            return e.toString().replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            return e.getClass().getSimpleName();
        }
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }
}
