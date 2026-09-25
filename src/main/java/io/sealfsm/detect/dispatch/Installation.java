package io.sealfsm.detect.dispatch;

import io.sealfsm.detect.CarrierTransitionDetector;
import io.sealfsm.detect.DispatchCommitDetector;
import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.model.CommitEvidence;
import io.sealfsm.model.CommitForm;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtArrayWrite;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExecutableReferenceExpression;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldRead;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLambda;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtNewArray;
import spoon.reflect.code.CtNewClass;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtThrow;
import spoon.reflect.code.CtUnaryOperator;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtVariableRead;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtClass;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F36, thesis Decision 4: <em>is a produced successor ever installed as the
 * current state?</em>
 *
 * <p>A value-returning host proves that it <em>produces</em> a hierarchy value.
 * Its codomain says so, and that is all it says. A conversion within a sum type
 * has the same signature and the same body shape:
 *
 * <pre>{@code
 *   OrderState handle(OrderState.Placed p) { return new OrderState.Validated(...); }  // a transition
 *   Length toFeet(Length.Meters m)         { return new Length.Feet(m.v() * 3.28); } // a conversion
 * }</pre>
 *
 * <p>In the first, the order moves from Placed to Validated. In the second, the
 * metres value still exists when the method returns, and a separate value has been
 * derived from it. Only what happens to the result separates them. A transition's
 * result REPLACES the current state: it is written into the storage the state is
 * read from, handed to a mutator that does so, or re-entered as the selector of a
 * run-to-completion driver. A conversion's result is used as data.
 *
 * <p>Until F36 the tool read the codomain as the whole commit ({@code VALUE_RETURN}),
 * so a sum type with a family of converters was published as a machine
 * ({@code LIMITATIONS.md} L3). The rule here applies to every commit whose
 * successor leaves its host by {@code return}: {@code VALUE_RETURN},
 * {@code CARRIER_RETURN}, {@code POLY_CARRIER}, and a {@code LOCAL_ACCUMULATOR}
 * that is returned rather than stored. It applies at every locus: a per-state
 * override, a centralized switch, an {@code instanceof} chain, a typed handler, a
 * functional callable. A rule about what a commit IS has to hold at every locus or
 * at none, or one converter's verdict would depend on its spelling.
 *
 * <p>Commits that install inside the host need no caller, and are
 * {@link Verdict#IN_HOST}: a write to a hierarchy-typed field
 * ({@code FIELD_MUTATION}), a recognised mutator ({@code MUTATOR_ARGUMENT}), a
 * callee the k = 1 probe proved writes the root field, a context commit (F33).
 *
 * <p><b>What counts as installation.</b> The value is followed out of the host, up
 * to {@link #MAX_DEPTH} call boundaries, through conditionals, switch-expression
 * arms, locals, returns and arguments, until it reaches one of four sinks:
 * <ol>
 *   <li>an assignment to a field declared with the hierarchy ROOT. This is the same
 *       clause the commit probe proves a commit by
 *       ({@link CommitProbe#isRootFieldWrite}), so the two cannot disagree about
 *       what a state field is;</li>
 *   <li>an assignment back into the variable the call read its state from:
 *       {@code s = step(s)}, {@code current = current.next()};</li>
 *   <li>the argument of a recognised mutator ({@link MutatorRecognizer});</li>
 *   <li>the selector argument of a run-to-completion driver
 *       ({@link RunToCompletion}), whose next activation examines it.</li>
 * </ol>
 *
 * <p><b>Three ways to fail, kept apart.</b> Decision 4 requires that a conversion
 * never be published as a machine. It also requires that missing caller evidence
 * be read as uncertainty, not as proof of a conversion.
 * <ul>
 *   <li>{@link Verdict#NO_CALLER}: nothing in the source set calls the host. A
 *       library's public {@code transition(H, E)} looks like this, and so does a
 *       converter no one has called yet. <em>Uncertain.</em></li>
 *   <li>{@link Verdict#OPAQUE}: a caller hands the value somewhere the analysis
 *       does not follow. Examples: an unread library method, a collection, an
 *       object under construction, a lambda, a call whose runtime body is not
 *       unique, or the depth budget. <em>Uncertain.</em></li>
 *   <li>{@link Verdict#CONVERTED}: callers exist, and every one of them uses the
 *       value as data. It is read, compared, switched over, printed through an
 *       in-model method, or discarded. <em>Established</em> as a conversion within
 *       the source set.</li>
 * </ul>
 *
 * <p><b>Honest limits.</b> Local flow is followed flow-insensitively. Every read
 * of a local the value was assigned to is asked, including one that a later
 * reassignment makes unreachable, and that errs toward installation. A converted
 * value cached in a field declared with the root is read as installed. That is the
 * decision's own clause ("an assignment to a state field"), and a root-typed field
 * is what every other commit rule in the tool calls state storage. Callers outside
 * the source set cannot be seen. That bound is shared with every other
 * closed-world claim the tool makes.
 */
public final class Installation {

    /**
     * The number of call boundaries (a return to a caller, or an argument into a
     * callee) a value is followed across before the analysis gives up and reports
     * {@link Verdict#OPAQUE}. Deeper than the k = 2 successor fold on purpose,
     * because the two budgets bound different things. The fold bounds how far a
     * successor's IDENTITY is chased, and its errors are fabricated edges. This
     * bounds how far its DESTINATION is chased. An error here costs an acceptance
     * (uncertain rather than installed), never an edge. A helper returned by a
     * dispatcher, returned by a driver method, stored by a loop is three hops.
     * Overridable with {@code -Dsealfsm.maxInstallationDepth}, for a sweep only.
     */
    public static final int MAX_DEPTH = Integer.getInteger("sealfsm.maxInstallationDepth", 6);

    /** Which discovery route a site came from; the key the report is indexed on. */
    public enum Route { OVERRIDE, CARRIER, CENTRALIZED, PRODUCER, FUNCTIONAL, CONTEXT }

    /** Where, if anywhere, a site's successor is shown to become the current state. */
    public enum Verdict {
        /** Installed by the site itself, with no caller involved. */
        IN_HOST,
        /** A run-to-completion driver: the successor re-enters the site as its selector (F34). */
        REENTRY,
        /** A caller stores the returned successor back as the current state. */
        INSTALLED,
        /** No call to the host exists in the source set. Uncertain. */
        NO_CALLER,
        /** A caller hands the value somewhere the analysis does not follow. Uncertain. */
        OPAQUE,
        /** Every caller uses the value as data. Established as a conversion. */
        CONVERTED;

        /** Is the commit established, so the site's arms may be claimed as transitions? */
        public boolean established() {
            return this == IN_HOST || this == REENTRY || this == INSTALLED;
        }

        /** The evidence an established verdict adds to the machine's commit. */
        public CommitEvidence evidence() {
            return this == INSTALLED ? CommitEvidence.VIA_CALLER : CommitEvidence.DIRECT;
        }
    }

    /**
     * One site's verdict.
     *
     * @param route   the discovery route the site came from
     * @param site    the site itself
     * @param form    how the site hands its successor on: a return-based form, or
     *                the in-host form that made the verdict {@link Verdict#IN_HOST}
     * @param verdict where the successor goes
     * @param detail  the evidence, rendered for diagnostics and {@code --explain};
     *                never consulted by a decision
     */
    public record SiteVerdict(Route route, DispatchSite site, CommitForm form, Verdict verdict,
                              String detail) {
        /** {@code Type.method} of the site's host, for reports. */
        public String hostName() {
            return Installation.hostName(site.host());
        }
    }

    /** Every site's verdict for one root, looked up by route and dispatch node. */
    public static final class Report {
        private final List<SiteVerdict> verdicts;
        private final Map<CtElement, EnumMap<Route, SiteVerdict>> index = new IdentityHashMap<>();
        private final boolean plausible;

        Report(List<SiteVerdict> verdicts, boolean plausible) {
            this.verdicts = List.copyOf(verdicts);
            this.plausible = plausible;
            for (SiteVerdict v : verdicts) {
                index.computeIfAbsent(v.site().node(), k -> new EnumMap<>(Route.class)).put(v.route(), v);
            }
        }

        /** An empty report: nothing was judged, so nothing may be claimed on its basis. */
        public static Report empty() {
            return new Report(List.of(), false);
        }

        public List<SiteVerdict> all() {
            return verdicts;
        }

        /** The verdict for {@code site} on {@code route}, or {@code null} when it was not judged. */
        public SiteVerdict verdict(Route route, DispatchSite site) {
            if (site == null) return null;
            EnumMap<Route, SiteVerdict> byRoute = index.get(site.node());
            return byRoute == null ? null : byRoute.get(route);
        }

        /**
         * May {@code site}'s arms be claimed as transitions? {@code false} for a
         * site the report never judged. The two computations run over the same
         * model and produce the same nodes, so this cannot happen by accident, and
         * if it ever does, an unjudged site is not evidence of installation.
         */
        public boolean established(Route route, DispatchSite site) {
            SiteVerdict v = verdict(route, site);
            return v != null && v.verdict().established();
        }

        public boolean anyEstablished() {
            return verdicts.stream().anyMatch(v -> v.verdict().established());
        }

        /** The sites whose successor is not shown to be installed. */
        public List<SiteVerdict> unestablished() {
            return verdicts.stream().filter(v -> !v.verdict().established()).toList();
        }

        /**
         * Every unestablished site is an established conversion, and there is at
         * least one. Only then is a hierarchy rejected as a converter. A single
         * uncertain site keeps the verdict uncertain.
         */
        public boolean allConverted() {
            List<SiteVerdict> open = unestablished();
            return !open.isEmpty() && open.stream().allMatch(v -> v.verdict() == Verdict.CONVERTED);
        }

        /**
         * Do the unestablished sites amount to a dispatch? This means a
         * discrimination, or a family of per-state sites that fixes at least two
         * source states. That is the threshold an {@code instanceof} chain and the
         * carrier detector already apply, and the one F35 applied to typed handlers.
         * Decision 4 retires it as an ACCEPTANCE rule, because installation evidence
         * replaces it. It stays here as a PLAUSIBILITY rule for the candidate
         * channel: a lone uncalled converter is not evidence of a dispatch, so it is
         * a plain abstention and not a candidate.
         */
        public boolean plausibleDispatch() {
            return plausible;
        }
    }

    private Installation() {
    }

    /**
     * Judge every site for {@code root}.
     *
     * <p>{@code producers} must be the list {@link DispatchCommitDetector#find}
     * returned for the same root and model. {@link DispatchFinder#producerSites} is
     * built from it in the same order, which is how the extractor already pairs a
     * producer's commit with its site.
     */
    public static Report analyze(CtType<?> root, CtModel model, DispatchFinder.Sites sites,
                                 List<DispatchCommitDetector.Producer> producers) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
        String rootQn = root.getQualifiedName();
        Flow flow = new Flow(model, hierarchy, rootQn);
        List<SiteVerdict> out = new ArrayList<>();

        // Producers first: a stateful driver's own dispatch may install in the host,
        // and a centralized site on that host inherits the verdict (below).
        List<SiteVerdict> producerVerdicts = new ArrayList<>();
        Map<CtElement, SiteVerdict> inHostByHost = new IdentityHashMap<>();
        List<DispatchSite> producerSites = sites.producers();
        for (int i = 0; i < producerSites.size(); i++) {
            DispatchCommitDetector.Producer p = i < producers.size() ? producers.get(i) : null;
            SiteVerdict v = flow.judgeProducer(producerSites.get(i), p);
            producerVerdicts.add(v);
            if (v.verdict() == Verdict.IN_HOST) inHostByHost.putIfAbsent(v.site().host(), v);
        }

        for (DispatchSite site : sites.overrides()) {
            out.add(flow.judgeReturning(Route.OVERRIDE, site, CommitForm.VALUE_RETURN));
        }
        for (DispatchSite site : sites.carriers()) {
            out.add(flow.judgeReturning(Route.CARRIER, site, CommitForm.POLY_CARRIER));
        }
        for (DispatchSite site : sites.centralized()) {
            // F19's ownership rule, read the same way here. A host that is not
            // handed the state reads it from a field. When its own dispatch installs
            // the successor there, the value it then returns is a read-back of that
            // commit, so its callers are not where the transition is installed. The
            // extractor lets the producer own such a host for the same reason.
            SiteVerdict owner = inHostByHost.get(site.host());
            if (owner != null && site.host() instanceof CtMethod<?> m
                    && !StateMachineClassifier.takesHierarchyParameter(m, hierarchy)) {
                out.add(new SiteVerdict(Route.CENTRALIZED, site, owner.form(), Verdict.IN_HOST,
                        owner.detail() + "; the value the host returns is a read-back of that commit"));
                continue;
            }
            out.add(flow.judgeReturning(Route.CENTRALIZED, site, CommitForm.VALUE_RETURN));
        }
        for (DispatchSite site : sites.functional()) {
            out.add(flow.judgeFunctional(site));
        }
        out.addAll(producerVerdicts);
        for (DispatchSite site : sites.contextCommits()) {
            out.add(new SiteVerdict(Route.CONTEXT, site, CommitForm.MUTATOR_ARGUMENT, Verdict.IN_HOST,
                    "installs into a context from inside the per-state method (F33)"));
        }
        return new Report(out, plausible(root, out, hierarchy, rootQn));
    }

    /** See {@link Report#plausibleDispatch()}. */
    private static boolean plausible(CtType<?> root, List<SiteVerdict> verdicts, Set<String> hierarchy,
                                     String rootQn) {
        Set<String> overrideMembers = new LinkedHashSet<>();
        Set<String> typedSources = new LinkedHashSet<>();
        boolean carriers = false;
        for (SiteVerdict v : verdicts) {
            if (v.verdict().established()) continue;
            switch (v.route()) {
                case PRODUCER, FUNCTIONAL -> {
                    return true;
                }
                case CENTRALIZED -> {
                    if (!(v.site().host() instanceof CtMethod<?> m)) return true;
                    CtParameter<?> typed = StateMachineClassifier.typedSourceParameter(m, hierarchy, rootQn);
                    if (typed == null) return true;              // discriminates, or takes the root
                    typedSources.add(typed.getType().getQualifiedName());
                }
                case OVERRIDE -> {
                    if (v.site().host() instanceof CtMethod<?> m && m.getDeclaringType() != null) {
                        overrideMembers.add(m.getDeclaringType().getQualifiedName());
                    }
                }
                case CARRIER -> carriers = true;
                case CONTEXT -> { }
            }
        }
        if (typedSources.size() >= 2 || overrideMembers.size() >= 2) return true;
        return carriers && CarrierTransitionDetector.qualifies(root);
    }

    // ---- the flow ------------------------------------------------------------

    /** How a value is used once it leaves the point that produced it. Ordered by strength. */
    private enum Kind { NEUTRAL, VALUE, NO_CALLER, OPAQUE, SINK }

    /**
     * One use of the tracked value. {@code crossedCall} records whether it was
     * found after the value left its producing method, which is what separates
     * {@link Verdict#INSTALLED} from {@link Verdict#IN_HOST}.
     */
    private record Use(Kind kind, String detail, boolean crossedCall) {
        static final Use NEUTRAL = new Use(Kind.NEUTRAL, null, false);

        /** The stronger of two uses; the first found wins a tie, so reports are stable. */
        Use max(Use other) {
            return other.kind.ordinal() > kind.ordinal() ? other : this;
        }

        Use crossed() {
            return crossedCall ? this : new Use(kind, detail, true);
        }
    }

    /** The traversal, with the model-wide indexes it needs built once. */
    private static final class Flow {
        private final Set<String> hierarchy;
        private final String rootQn;
        private final CallTarget.Index targets;
        private final Map<String, List<CtInvocation<?>>> invocationsByName = new HashMap<>();
        private final Map<String, List<CtExecutableReferenceExpression<?, ?>>> refsByName = new HashMap<>();
        /** Hosts whose destination is being computed: a cycle contributes nothing. */
        private final Set<CtExecutable<?>> onStack = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Variables whose reads are being expanded: a def-use cycle contributes nothing. */
        private final Set<CtVariable<?>> expanding = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Top-level answers only; see {@link #hostUse}. */
        private final Map<CtExecutable<?>, Use[]> memo = new IdentityHashMap<>();

        Flow(CtModel model, Set<String> hierarchy, String rootQn) {
            this.hierarchy = hierarchy;
            this.rootQn = rootQn;
            this.targets = new CallTarget.Index(model);
            for (CtInvocation<?> inv : model.getElements(new TypeFilter<>(CtInvocation.class))) {
                String name = simpleName(inv.getExecutable());
                if (name != null) invocationsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(inv);
            }
            for (CtExecutableReferenceExpression<?, ?> ref
                    : model.getElements(new TypeFilter<>(CtExecutableReferenceExpression.class))) {
                String name = simpleName(ref.getExecutable());
                if (name != null) refsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(ref);
            }
        }

        // ---- per-site entry points ------------------------------------------

        /** A site whose successor is its host's return value (or a carrier's component). */
        SiteVerdict judgeReturning(Route route, DispatchSite site, CommitForm form) {
            if (!(site.host() instanceof CtMethod<?> host)) {
                return new SiteVerdict(route, site, form, Verdict.OPAQUE, "the site has no named host");
            }
            if (RunToCompletion.reentryIndex(host, hierarchy, rootQn, targets) >= 0) {
                return new SiteVerdict(route, site, form, Verdict.REENTRY,
                        "re-enters itself with the successor as its next state (run to completion)");
            }
            // A carrier is the host's DECLARED return type, never the route it was
            // found by: the carrier detector's list also holds methods returning the
            // state itself (a bare peer production), and following those as a
            // wrapper would refuse the very field write that installs them.
            CtTypeReference<?> ret = host.getType();
            boolean carrier = ret != null && !hierarchy.contains(ret.getQualifiedName());
            return toVerdict(route, site, form, topLevel(host, carrier));
        }

        /**
         * A producer: a switch or chain over the state whose commit
         * {@link DispatchCommitDetector} identified.
         */
        SiteVerdict judgeProducer(DispatchSite site, DispatchCommitDetector.Producer p) {
            if (p == null) {
                return new SiteVerdict(Route.PRODUCER, site, null, Verdict.OPAQUE,
                        "no producer paired with the site");
            }
            CommitForm form = p.commit();
            if (p.evidence() == CommitEvidence.VIA_CALLEE) {
                return new SiteVerdict(Route.PRODUCER, site, form, Verdict.IN_HOST,
                        "a callee the k = 1 probe read writes the root-typed state field");
            }
            switch (form) {
                case FIELD_MUTATION -> {
                    return new SiteVerdict(Route.PRODUCER, site, form, Verdict.IN_HOST,
                            "written to a hierarchy-typed field at the dispatch");
                }
                case MUTATOR_ARGUMENT -> {
                    return new SiteVerdict(Route.PRODUCER, site, form, Verdict.IN_HOST,
                            "handed to a recognised mutator at the dispatch");
                }
                case LOCAL_ACCUMULATOR -> {
                    return toVerdict(Route.PRODUCER, site, form, accumulatorUse(site, p));
                }
                default -> {
                    return judgeReturning(Route.PRODUCER, site, form);
                }
            }
        }

        /**
         * A lambda or anonymous-class callable. Its successor leaves by whoever
         * invokes it, which the analysis follows only where the callable is bound to
         * a variable in the model and invoked through it.
         */
        SiteVerdict judgeFunctional(DispatchSite site) {
            CtElement callable = site.host();
            CtExpression<?> value = callable instanceof CtLambda<?> lam ? lam
                    : callable instanceof CtMethod<?> m ? m.getParent(CtNewClass.class) : null;
            if (value == null) {
                return new SiteVerdict(Route.FUNCTIONAL, site, CommitForm.VALUE_RETURN, Verdict.OPAQUE,
                        "a functional callable the analysis cannot place");
            }
            return toVerdict(Route.FUNCTIONAL, site, CommitForm.VALUE_RETURN, callableUse(value));
        }

        private SiteVerdict toVerdict(Route route, DispatchSite site, CommitForm form, Use use) {
            Verdict v = switch (use.kind()) {
                case SINK -> use.crossedCall() ? Verdict.INSTALLED : Verdict.IN_HOST;
                case OPAQUE -> Verdict.OPAQUE;
                case VALUE -> Verdict.CONVERTED;
                case NO_CALLER, NEUTRAL -> Verdict.NO_CALLER;
            };
            String detail = use.detail() != null ? use.detail()
                    : "no call to " + hostName(site.host()) + " exists in the source set";
            return new SiteVerdict(route, site, form, v, detail);
        }

        /** A local accumulator: where the local the dispatch filled goes next. */
        private Use accumulatorUse(DispatchSite site, DispatchCommitDetector.Producer p) {
            CtElement node = p.dispatch();
            if (node instanceof CtSwitchExpression<?, ?> sw) return valueUse(sw, Set.of(), false, 0);
            // A chain's commit is an assignment inside a branch: follow each local it writes.
            Use best = Use.NEUTRAL;
            for (CtAssignment<?, ?> a : node.getElements(new TypeFilter<>(CtAssignment.class))) {
                if (CommitClassifier.ofTarget(a.getAssigned(), hierarchy) != CommitForm.LOCAL_ACCUMULATOR) {
                    continue;
                }
                CtVariable<?> v = declarationOf(a.getAssigned());
                if (v != null) best = best.max(localUse(v, Set.of(), false, 0));
            }
            return best.kind() == Kind.NEUTRAL
                    ? new Use(Kind.OPAQUE, "the accumulated local could not be followed", false) : best;
        }

        // ---- following a value ----------------------------------------------

        /**
         * Where the value a call to {@code host} returns goes, over every call to
         * it. Memoised only for top-level questions. An inner answer can be cut by
         * a cycle or by the budget, and reusing it elsewhere would make one site's
         * verdict depend on which site was asked first.
         */
        private Use topLevel(CtMethod<?> host, boolean carrier) {
            Use[] slot = memo.computeIfAbsent(host, k -> new Use[2]);
            int k = carrier ? 1 : 0;
            if (slot[k] == null) slot[k] = hostUse(host, carrier, 0);
            return slot[k];
        }

        private Use hostUse(CtMethod<?> host, boolean carrier, int depth) {
            if (depth > MAX_DEPTH) {
                return new Use(Kind.OPAQUE, "followed beyond the depth budget of " + MAX_DEPTH
                        + " call(s) without reaching the state", true);
            }
            if (!onStack.add(host)) return Use.NEUTRAL;
            try {
                List<CtInvocation<?>> calls = callersOf(host);
                int refs = methodReferencesTo(host);
                int unbound = unboundSameNameCalls(host);
                if (calls.isEmpty() && refs == 0 && unbound == 0) {
                    return new Use(Kind.NO_CALLER, "no call to " + hostName(host)
                            + " exists in the source set", depth > 0);
                }
                Use best = Use.NEUTRAL;
                for (CtInvocation<?> inv : calls) {
                    best = best.max(valueUse(inv, stateInputs(inv), carrier, depth));
                    if (best.kind() == Kind.SINK) break;
                }
                if (best.kind() != Kind.SINK && refs > 0) {
                    best = best.max(new Use(Kind.OPAQUE, hostName(host)
                            + " is passed as a method reference, and where it is invoked is not followed", true));
                }
                if (best.kind() != Kind.SINK && unbound > 0) {
                    best = best.max(new Use(Kind.OPAQUE, unbound + " call(s) named "
                            + host.getSimpleName() + " did not bind to a declaration, so they may reach "
                            + hostName(host), true));
                }
                if (best.kind() == Kind.NEUTRAL) {
                    return new Use(Kind.NO_CALLER, "no call to " + hostName(host)
                            + " exists outside its own recursion", depth > 0);
                }
                return best.crossed();
            } finally {
                onStack.remove(host);
            }
        }

        /**
         * Where {@code e}'s value goes, read off its parent. {@code inputs} are the
         * variables the originating call read its state from, which is what makes
         * {@code s = step(s)} a store-back. {@code carrier} is set while the value
         * is still a carrier and only its hierarchy-typed component is the successor.
         */
        private Use valueUse(CtExpression<?> e, Set<CtVariable<?>> inputs, boolean carrier, int depth) {
            CtElement p = parentOf(e);
            if (p == null) return opaque("the value has no enclosing expression", e);
            try {
                if (p instanceof CtConditional<?> c) {
                    if (c.getThenExpression() == e || c.getElseExpression() == e) {
                        return valueUse(c, inputs, carrier, depth);
                    }
                    return value("tested as a condition", e);
                }
                if (p instanceof CtYieldStatement y) {
                    CtElement arm = parentOf(y);
                    if (arm instanceof CtCase<?> c && parentOf(c) instanceof CtSwitch<?>) {
                        return value("the result is discarded (a switch statement arm)", e);
                    }
                    CtSwitchExpression<?, ?> sw = y.getParent(CtSwitchExpression.class);
                    return sw != null ? valueUse(sw, inputs, carrier, depth)
                            : opaque("a yield outside a switch expression", e);
                }
                if (p instanceof CtCase<?> c) {
                    return parentOf(c) instanceof CtSwitchExpression<?, ?> sw
                            ? valueUse(sw, inputs, carrier, depth)
                            : value("the result is discarded (a switch statement arm)", e);
                }
                if (p instanceof CtReturn<?> r) return returnUse(r, e, carrier, depth);
                if (p instanceof CtAssignment<?, ?> a && a.getAssignment() == e) {
                    return assignmentUse(a, inputs, carrier, depth);
                }
                if (p instanceof CtLocalVariable<?> lv && lv.getDefaultExpression() == e) {
                    return localUse(lv, inputs, carrier, depth);
                }
                if (p instanceof CtInvocation<?> inv) {
                    if (inv.getTarget() == e) return receiverUse(inv, inputs, carrier, depth);
                    int i = indexOf(inv.getArguments(), e);
                    if (i >= 0) return argumentUse(inv, i, carrier, depth);
                }
                if (p instanceof CtFieldRead<?> fr && fr.getTarget() == e) {
                    return carrier && isHierarchyTyped(fr)
                            ? valueUse(fr, inputs, false, depth)
                            : value("one of its fields is read", e);
                }
                if (p instanceof CtConstructorCall<?> || p instanceof CtNewArray<?>) {
                    return opaque("it is stored inside a newly constructed object", e);
                }
                if (p instanceof CtBinaryOperator<?> || p instanceof CtUnaryOperator<?>) {
                    return value("it is used as an operand", e);
                }
                if (p instanceof CtAbstractSwitch<?> sw && sw.getSelector() == e) {
                    return value("it is switched over", e);
                }
                if (p instanceof CtLambda<?>) return opaque("it is returned from a lambda", e);
                if (p instanceof CtThrow) return value("it is thrown", e);
                if (CommitProbe.isStatementPosition(e)) return value("the result is discarded", e);
                return opaque("it is used in a " + p.getClass().getSimpleName().replace("Impl", ""), e);
            } catch (Throwable t) {
                return opaque("its use could not be read", e);
            }
        }

        private Use returnUse(CtReturn<?> r, CtExpression<?> e, boolean carrier, int depth) {
            CtExecutable<?> ex = r.getParent(CtExecutable.class);
            if (ex instanceof CtMethod<?> m && !isFunctionalBody(m)) {
                return hostUse(m, carrier, depth + 1).crossed();
            }
            return opaque("it is returned from a lambda or an anonymous class", e);
        }

        private Use assignmentUse(CtAssignment<?, ?> a, Set<CtVariable<?>> inputs, boolean carrier, int depth) {
            if (a instanceof CtOperatorAssignment<?, ?>) return value("it is used in a compound assignment", a);
            if (!carrier && CommitProbe.isRootFieldWrite(a, hierarchy, rootQn)) {
                return sink("stored into the root-typed field '" + variableName(a.getAssigned())
                        + "' in " + where(a), a);
            }
            CtExpression<?> target = a.getAssigned();
            if (target instanceof CtArrayWrite<?>) return opaque("it is stored into an array", a);
            CtVariable<?> v = declarationOf(target);
            if (v instanceof CtLocalVariable<?> || v instanceof CtParameter<?>) {
                if (!carrier && inputs.contains(v)) {
                    return sink("replaces the state variable '" + v.getSimpleName()
                            + "' it was computed from, in " + where(a), a);
                }
                return localUse(v, inputs, carrier, depth);
            }
            if (v instanceof CtField<?> f) {
                CtTypeReference<?> ft = f.getType();
                if (!carrier && ft != null && hierarchy.contains(ft.getQualifiedName())) {
                    return value("stored into the field '" + f.getSimpleName()
                            + "', declared with a proper member, which cannot hold the other states", a);
                }
                return opaque("stored into the field '" + f.getSimpleName() + "' in " + where(a), a);
            }
            return opaque("assigned to something the analysis cannot name", a);
        }

        /**
         * Every read of {@code v} in its scope. The analysis is flow-insensitive, so
         * it asks each read, including one that a reassignment makes unreachable.
         * That errs toward installation, and the class javadoc says so.
         */
        private Use localUse(CtVariable<?> v, Set<CtVariable<?>> inputs, boolean carrier, int depth) {
            if (!expanding.add(v)) return Use.NEUTRAL;
            try {
                CtExecutable<?> scope = v.getParent(CtExecutable.class);
                if (scope == null) return opaque("the variable '" + v.getSimpleName() + "' has no scope", v);
                List<CtVariableRead<?>> reads = readsOf(v, scope);
                if (reads.isEmpty()) {
                    return value("assigned to '" + v.getSimpleName() + "', which is never read", v);
                }
                Use best = Use.NEUTRAL;
                for (CtVariableRead<?> read : reads) {
                    best = best.max(valueUse(read, inputs, carrier, depth));
                    if (best.kind() == Kind.SINK) break;
                }
                return best;
            } finally {
                expanding.remove(v);
            }
        }

        private Use receiverUse(CtInvocation<?> inv, Set<CtVariable<?>> inputs, boolean carrier, int depth) {
            if (carrier) {
                if (isHierarchyTyped(inv)) return valueUse(inv, inputs, false, depth);
                for (CtExpression<?> arg : inv.getArguments()) {
                    if (arg instanceof CtLambda<?> || arg instanceof CtExecutableReferenceExpression<?, ?>) {
                        return opaque("the carrier is handed to " + simpleName(inv.getExecutable())
                                + " with a callback, which is not followed", inv);
                    }
                }
                return value("another part of the carrier is read (" + simpleName(inv.getExecutable()) + ")", inv);
            }
            return value("it is the receiver of " + simpleName(inv.getExecutable()) + "()", inv);
        }

        private Use argumentUse(CtInvocation<?> inv, int position, boolean carrier, int depth) {
            String name = simpleName(inv.getExecutable());
            CtMethod<?> bound = CallTarget.boundDeclaration(inv);
            if (bound == null) {
                return opaque("it is passed to " + name + ", which did not bind to a declaration", inv);
            }
            if (!carrier && commitsArgument(bound, position)) {
                return sink("handed to " + describeCommitter(bound, position) + " in " + where(inv), inv);
            }
            CallTarget.Result target = CallTarget.of(inv, targets);
            if (!target.unique()) {
                return opaque("it is passed to " + name + ", whose runtime body is not unique ("
                        + target.refusal() + ")", inv);
            }
            CtMethod<?> callee = target.method();
            if (callee != bound && !carrier && commitsArgument(callee, position)) {
                return sink("handed to " + describeCommitter(callee, position) + " in " + where(inv), inv);
            }
            if (callee.getBody() == null || CalleeBody.isShadow(callee)) {
                return opaque("it is passed to " + name + ", whose body was not read", inv);
            }
            List<CtParameter<?>> params = callee.getParameters();
            if (position >= params.size()
                    || (params.get(params.size() - 1).isVarArgs() && position >= params.size() - 1)) {
                return opaque("it is passed to " + name + " as a variable argument", inv);
            }
            if (depth + 1 > MAX_DEPTH) {
                return new Use(Kind.OPAQUE, "followed beyond the depth budget of " + MAX_DEPTH
                        + " call(s) without reaching the state", true);
            }
            return localUse(params.get(position), Set.of(), carrier, depth + 1).crossed();
        }

        /** Is passing a hierarchy value in {@code position} of {@code m} an installation? */
        private boolean commitsArgument(CtMethod<?> m, int position) {
            if (position == 0 && MutatorRecognizer.commitsItsArgument(m, hierarchy, rootQn)) return true;
            return RunToCompletion.reentersThrough(m, position, hierarchy, rootQn, targets);
        }

        private String describeCommitter(CtMethod<?> m, int position) {
            return (position == 0 && MutatorRecognizer.commitsItsArgument(m, hierarchy, rootQn)
                    ? "the mutator " : "the run-to-completion driver ") + hostName(m);
        }

        /**
         * A functional value: followed to the variable it is bound to, and from
         * there to each invocation through that variable, whose value is the
         * callable's result.
         */
        private Use callableUse(CtExpression<?> callable) {
            CtElement p = parentOf(callable);
            CtVariable<?> bound = null;
            if (p instanceof CtLocalVariable<?> lv && lv.getDefaultExpression() == callable) bound = lv;
            if (p instanceof CtField<?> f && f.getDefaultExpression() == callable) bound = f;
            if (p instanceof CtAssignment<?, ?> a && a.getAssignment() == callable) bound = declarationOf(a.getAssigned());
            if (p instanceof CtInvocation<?> inv && bound == null) {
                // Handed to a method: followed into it when its body is in the model
                // and is the one that runs, and there the parameter plays the role
                // a variable would. Handed to a library method, it is invoked out of
                // sight, which is L2's library container.
                int i = indexOf(inv.getArguments(), callable);
                CallTarget.Result target = i < 0 ? null : CallTarget.of(inv, targets);
                if (target != null && target.unique() && target.method().getBody() != null
                        && !CalleeBody.isShadow(target.method())
                        && i < target.method().getParameters().size()) {
                    bound = target.method().getParameters().get(i);
                }
            }
            if (bound == null) {
                String what = p instanceof CtInvocation<?> inv
                        ? "handed to " + simpleName(inv.getExecutable()) + ", which invokes it out of sight"
                        : "not bound to a variable the analysis can follow";
                return new Use(Kind.OPAQUE, "the callable is " + what, true);
            }
            CtElement scope = bound instanceof CtField<?> f ? f.getDeclaringType()
                    : bound.getParent(CtExecutable.class);
            if (scope == null) return new Use(Kind.OPAQUE, "the callable's variable has no scope", true);
            Use best = Use.NEUTRAL;
            boolean invoked = false;
            for (CtVariableRead<?> read : readsOf(bound, scope)) {
                if (parentOf(read) instanceof CtInvocation<?> inv && inv.getTarget() == read) {
                    invoked = true;
                    best = best.max(valueUse(inv, stateInputs(inv), false, 1).crossed());
                } else {
                    best = best.max(new Use(Kind.OPAQUE, "the callable escapes through '"
                            + bound.getSimpleName() + "'", true));
                }
                if (best.kind() == Kind.SINK) break;
            }
            if (!invoked && best.kind() == Kind.NEUTRAL) {
                return new Use(Kind.NO_CALLER, "the callable bound to '" + bound.getSimpleName()
                        + "' is never invoked in the source set", true);
            }
            return best;
        }

        // ---- indexes and small helpers ----------------------------------------

        /**
         * Calls that can run {@code host}: bound to it, or to a method it
         * overrides. A call bound to the abstract {@code TrafficLight.next()} runs
         * {@code Red.next()} when the receiver is a {@code Red}. The override test is
         * a declaration fact, and the simple name only prefilters.
         */
        private List<CtInvocation<?>> callersOf(CtMethod<?> host) {
            List<CtInvocation<?>> out = new ArrayList<>();
            for (CtInvocation<?> inv : invocationsByName.getOrDefault(host.getSimpleName(), List.of())) {
                CtMethod<?> bound = CallTarget.boundDeclaration(inv);
                if (bound != null && (bound == host || overrides(host, bound))) out.add(inv);
            }
            return out;
        }

        private int methodReferencesTo(CtMethod<?> host) {
            int n = 0;
            for (CtExecutableReferenceExpression<?, ?> ref : refsByName.getOrDefault(host.getSimpleName(), List.of())) {
                try {
                    CtExecutableReference<?> exe = ref.getExecutable();
                    if (exe != null && exe.getExecutableDeclaration() instanceof CtMethod<?> m
                            && (m == host || overrides(host, m))) {
                        n++;
                    }
                } catch (Throwable ignored) {
                    // an unreadable reference is not evidence either way
                }
            }
            return n;
        }

        /**
         * Same-name, same-arity calls that bound to nothing. Under
         * {@code noClasspath} they may be calls to {@code host}. They are counted so
         * an uncalled host is not reported as uncalled on their account, and a
         * would-be conversion is not established over them.
         */
        private int unboundSameNameCalls(CtMethod<?> host) {
            int n = 0;
            for (CtInvocation<?> inv : invocationsByName.getOrDefault(host.getSimpleName(), List.of())) {
                if (CallTarget.boundDeclaration(inv) == null
                        && inv.getArguments().size() == host.getParameters().size()) {
                    n++;
                }
            }
            return n;
        }

        private static boolean overrides(CtMethod<?> host, CtMethod<?> bound) {
            try {
                return host.isOverriding(bound);
            } catch (Throwable t) {
                return false;
            }
        }

        /** The hierarchy-typed variables a call reads its state from: arguments and receiver. */
        private Set<CtVariable<?>> stateInputs(CtInvocation<?> inv) {
            Set<CtVariable<?>> out = Collections.newSetFromMap(new IdentityHashMap<>());
            for (CtExpression<?> a : inv.getArguments()) addStateInput(a, out);
            addStateInput(inv.getTarget(), out);
            return out;
        }

        private void addStateInput(CtExpression<?> e, Set<CtVariable<?>> out) {
            if (e instanceof CtVariableRead<?> read && isHierarchyTyped(read)) {
                CtVariable<?> d = declarationOf(read);
                if (d != null) out.add(d);
            }
        }

        private boolean isHierarchyTyped(CtExpression<?> e) {
            try {
                CtTypeReference<?> t = e == null ? null : e.getType();
                return t != null && hierarchy.contains(t.getQualifiedName());
            } catch (Throwable ex) {
                return false;
            }
        }

        private static Use sink(String detail, CtElement at) {
            return new Use(Kind.SINK, detail, false);
        }

        private static Use value(String detail, CtElement at) {
            return new Use(Kind.VALUE, "used as data: " + detail + " in " + where(at), false);
        }

        private static Use opaque(String detail, CtElement at) {
            return new Use(Kind.OPAQUE, "not followed: " + detail + " in " + where(at), false);
        }
    }

    // ---- shared static helpers ------------------------------------------------

    private static boolean isFunctionalBody(CtMethod<?> m) {
        CtType<?> owner = m.getDeclaringType();
        return owner instanceof CtClass<?> c && c.isAnonymous();
    }

    private static List<CtVariableRead<?>> readsOf(CtVariable<?> v, CtElement scope) {
        List<CtVariableRead<?>> out = new ArrayList<>();
        try {
            for (CtVariableRead<?> read : scope.getElements(new TypeFilter<>(CtVariableRead.class))) {
                CtVariableReference<?> ref = read.getVariable();
                if (ref == null || !v.getSimpleName().equals(ref.getSimpleName())) continue;
                if (ref.getDeclaration() == v) out.add(read);
            }
        } catch (Throwable ignored) {
            // an unreadable scope yields no reads, which reads as "not followed" upstream
        }
        return out;
    }

    private static CtVariable<?> declarationOf(CtExpression<?> e) {
        try {
            if (e instanceof CtVariableAccess<?> va && va.getVariable() != null) {
                return va.getVariable().getDeclaration();
            }
        } catch (Throwable ignored) {
            // unresolvable: no declaration to name
        }
        return null;
    }

    private static String variableName(CtExpression<?> e) {
        if (e instanceof CtVariableAccess<?> va && va.getVariable() != null) {
            return va.getVariable().getSimpleName();
        }
        return String.valueOf(e);
    }

    private static int indexOf(List<CtExpression<?>> list, CtExpression<?> e) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == e) return i;
        }
        return -1;
    }

    private static CtElement parentOf(CtElement e) {
        try {
            return e != null && e.isParentInitialized() ? e.getParent() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String simpleName(CtExecutableReference<?> exe) {
        return exe == null ? null : exe.getSimpleName();
    }

    /** {@code Type.method} of the executable enclosing {@code e}, for evidence text. */
    static String where(CtElement e) {
        if (e == null) return "?";
        CtExecutable<?> ex = e instanceof CtExecutable<?> x ? x : e.getParent(CtExecutable.class);
        CtMethod<?> m = ex instanceof CtMethod<?> mm ? mm : e.getParent(CtMethod.class);
        return m == null ? "a constructor or an initializer" : hostName(m);
    }

    /** {@code Type.method}, or the locus name for a site with no named host. */
    static String hostName(CtElement host) {
        if (host instanceof CtMethod<?> m) {
            CtType<?> declaring = m.getDeclaringType();
            String owner = declaring == null ? "?"
                    : declaring instanceof CtClass<?> c && c.isAnonymous()
                            ? "anonymous " + declaringTypeOfAnonymous(declaring) : declaring.getSimpleName();
            return owner + "." + m.getSimpleName();
        }
        if (host instanceof CtLambda<?>) return "a lambda in " + where(host.getParent());
        return String.valueOf(host);
    }

    private static String declaringTypeOfAnonymous(CtType<?> anon) {
        CtMethod<?> encl = anon.getParent(CtMethod.class);
        return encl == null ? "class" : "class in " + hostName(encl);
    }
}
