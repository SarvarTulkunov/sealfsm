package io.sealfsm.extract;

import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import spoon.reflect.CtModel;
import spoon.reflect.code.BinaryOperatorKind;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBinaryOperator;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtCatch;
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
import spoon.reflect.code.CtTry;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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

    private static final Set<String> NEUTRAL_METHOD_NAMES =
            Set.of("next", "transition", "step", "advance", "nextstate", "transitionto", "tick");

    /** Conventional state-mutator names recognised regardless of body (F2). */
    private static final Set<String> MUTATOR_NAMES =
            Set.of("setstate", "changestate", "transitionto", "goto", "setcurrent", "become");

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

    // F2: mutation-encoding context, populated only while the GoF/mutation
    // fallback runs (a hierarchy with no return-based transition method). Left
    // empty for distributed/centralized extraction, so those paths are unchanged.
    private boolean mutationMode = false;
    private Set<String> stateFieldNames = Set.of();
    private Set<String> mutatorNames = Set.of();

    // F3: bounded inter-procedural resolution. k = 2 keeps the fixed analysis
    // terminating; the stack also detects recursion cycles.
    private static final int MAX_INTERPROC_DEPTH = 2;
    private final Deque<String> interProcStack = new ArrayDeque<>();
    private int interProcResolvedEdges = 0;

    // F4: the closed-world event alphabet Σ enumerated from the sealed/enum event
    // parameter of centralized transition functions, plus the qualified names of
    // that event type (root + members) so a switch-over-event can be recognised
    // and its arms attributed to the matched event.
    private final Set<String> alphabet = new LinkedHashSet<>();
    private final Set<String> eventQualifiedNames = new LinkedHashSet<>();

    public TransitionExtractor(Set<String> hierarchyQualifiedNames, String rootQualifiedName) {
        this.hierarchyQualifiedNames = hierarchyQualifiedNames;
        this.rootQualifiedName = rootQualifiedName;
        this.resolver = new TransitionResolver(hierarchyQualifiedNames, rootQualifiedName);
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    /** The event alphabet Σ recovered from sealed/enum event parameters (F4). */
    public Set<String> alphabet() {
        return alphabet;
    }

    public List<Transition> extract(CtType<?> root, CtModel model) {
        Set<Transition> out = new LinkedHashSet<>();
        List<CtMethod<?>> distributed = StateMachineClassifier.findDistributedTransitionMethods(root);
        List<CtMethod<?>> centralized = StateMachineClassifier.findCentralizedTransitionMethods(root, model);
        // F7: transition callables expressed as lambdas / anonymous-class methods.
        List<CtElement> functional = StateMachineClassifier.findFunctionalTransitionCallables(root, model);

        // The concrete states the selector can be, used to seed the functional
        // walk's entry from-set (finding F7).
        this.concreteStateSimpleNames = new LinkedHashSet<>();
        for (CtType<?> t : StateMachineClassifier.hierarchyTypes(root)) {
            if (!t.getQualifiedName().equals(rootQualifiedName)) {
                concreteStateSimpleNames.add(t.getSimpleName());
            }
        }

        // F3: a transition function that is *invoked* by another transition
        // function is an inter-procedural helper — its return values are folded
        // into the caller at the call site, so extracting it standalone would
        // double-count and emit spurious undetermined-origin edges. Exclude those.
        Set<String> helperSignatures = interproceduralHelperSignatures(distributed, centralized);

        for (CtMethod<?> m : distributed) {
            if (!helperSignatures.contains(m.getSignature())) extractDistributed(m, out);
        }
        for (CtMethod<?> m : centralized) {
            if (!helperSignatures.contains(m.getSignature())) extractCentralized(m, out);
        }
        for (CtElement callable : functional) {
            extractFunctional(callable, out);
        }
        // F2: GoF / field-mutation encoding. Run only as a fallback when no
        // return-based transition method exists, so a hierarchy that already
        // exposes a functional transition is left untouched — a context that
        // merely stores a functional result is not re-mined as a transition here.
        if (distributed.isEmpty() && centralized.isEmpty() && functional.isEmpty()) {
            extractMutationEncoding(root, model, out);
        }
        if (interProcResolvedEdges > 0) {
            diagnostics.add(interProcResolvedEdges + " transition target(s) resolved via bounded "
                    + "inter-procedural summaries (depth ≤ " + MAX_INTERPROC_DEPTH
                    + "); precision-sensitive — audit separately");
        }
        return new ArrayList<>(out);
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
        // The from-state is fixed to the declaring class; a switch inside the
        // body dispatches on the event, not on the state, so from is preserved.
        walk(method.getBody(), declaring.getSimpleName(), eventName(method), null, out);
    }

    // ---- centralized (single transition function) ----------------------------

    private void extractCentralized(CtMethod<?> method, Set<Transition> out) {
        // F4: recover the closed-world event alphabet Σ from the event parameter
        // (and register the event type so a nested switch-over-event can label
        // each arm). Done before the walk so walkSwitch sees eventQualifiedNames.
        enumerateEventAlphabet(method);
        if (!containsStateDispatchSwitch(method)) {
            // No switch we could attribute from-states to. Walk anyway so produced
            // targets remain visible (recorded with an undetermined origin), and
            // flag for manual review.
            diagnostics.add("centralized method '" + method.getSignature()
                    + "' has no recognised switch over the state type; "
                    + "from-states could not be attributed");
        }
        // from starts unknown and is set per matched state-pattern case; the event
        // label is null until a switch-over-event arm names it (F4).
        walk(method.getBody(), null, null, null, out);
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
        for (CtParameter<?> p : method.getParameters()) {
            CtTypeReference<?> pt = p.getType();
            if (pt == null || hierarchyQualifiedNames.contains(pt.getQualifiedName())) {
                continue; // skip the state selector (and untyped params)
            }
            CtType<?> decl = pt.getTypeDeclaration();
            if (decl == null) continue;

            List<String> symbols = new ArrayList<>();
            if (decl instanceof CtEnum<?> en) {
                for (CtEnumValue<?> v : en.getEnumValues()) symbols.add(v.getSimpleName());
            } else if (SpoonCompat.isSealed(decl)) {
                for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(decl)) {
                    symbols.add(ref.getSimpleName());
                }
            } else {
                continue; // not a closed event type — no exact Σ to recover
            }
            if (symbols.isEmpty()) continue;

            eventQualifiedNames.add(pt.getQualifiedName());
            for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(decl)) {
                eventQualifiedNames.add(ref.getQualifiedName());
            }
            alphabet.addAll(symbols);
        }
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
                out.add(Transition.resolved(StateMachine.INITIAL_PSEUDO_STATE, cand.targetSimpleName(), event, g));
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
            return t.getSimpleName();
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
     * enclosing method that supplies it (neutral names elided as elsewhere).
     */
    private String functionalEventLabel(CtElement callable) {
        try {
            CtMethod<?> enclosing = callable.getParent(CtMethod.class);
            return enclosing == null ? null : eventName(enclosing);
        } catch (Throwable t) {
            return null;
        }
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
        mutatorNames = findMutatorNames(model);
        if (stateFieldNames.isEmpty() && mutatorNames.isEmpty()) return;

        mutationMode = true;
        try {
            for (CtMethod<?> m : findMutationMethods(model)) {
                CtType<?> declaring = m.getDeclaringType();
                // GoF callback (`class Closed { void onLock(ctx){ ctx.setState(...); } }`):
                // the from-state is the declaring state class. A method that
                // dispatches on the state field instead leaves from null here and
                // has it set per matched arm by walkSwitch.
                String from = declaring != null
                        && hierarchyQualifiedNames.contains(declaring.getQualifiedName())
                        ? declaring.getSimpleName()
                        : null;
                // Mutation-style event labelling is future work (as for
                // centralized), so the event label stays null.
                walk(m.getBody(), from, null, null, out);
            }
        } finally {
            mutationMode = false;
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
     * Simple names of <em>mutator</em> methods: a single hierarchy-typed parameter
     * plus either a state-field assignment in the body or a conventional setter
     * name ({@code setState}/{@code changeState}/{@code transitionTo}/{@code goTo}).
     */
    private Set<String> findMutatorNames(CtModel model) {
        Set<String> names = new LinkedHashSet<>();
        for (CtMethod<?> m : model.getElements(new TypeFilter<>(CtMethod.class))) {
            List<CtParameter<?>> ps = m.getParameters();
            if (ps.size() != 1) continue;
            CtTypeReference<?> pt = ps.get(0).getType();
            if (pt == null || !hierarchyQualifiedNames.contains(pt.getQualifiedName())) continue;
            boolean assignsField = m.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                    .anyMatch(a -> isStateFieldWrite(a.getAssigned()));
            if (assignsField || MUTATOR_NAMES.contains(m.getSimpleName().toLowerCase())) {
                names.add(m.getSimpleName());
            }
        }
        return names;
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
            if (mutatorNames.contains(m.getSimpleName()) && m.getParameters().size() == 1) {
                continue; // the setter itself
            }
            boolean writesField = m.getElements(new TypeFilter<>(CtAssignment.class)).stream()
                    .anyMatch(a -> isStateFieldWrite(a.getAssigned()));
            boolean callsMutator = m.getElements(new TypeFilter<>(CtInvocation.class)).stream()
                    .anyMatch(this::isMutatorCall);
            if (writesField || callsMutator) out.add(m);
        }
        return out;
    }

    /** Is {@code lhs} a write to a state field (a field of a hierarchy type)? */
    private boolean isStateFieldWrite(CtExpression<?> lhs) {
        if (lhs instanceof CtFieldAccess<?> fa) {
            return fa.getVariable() != null && stateFieldNames.contains(fa.getVariable().getSimpleName());
        }
        if (lhs instanceof CtVariableAccess<?> va) {
            return va.getVariable() != null && stateFieldNames.contains(va.getVariable().getSimpleName());
        }
        return false;
    }

    /** Is {@code inv} a call to a recognised state mutator? */
    private boolean isMutatorCall(CtInvocation<?> inv) {
        try {
            return inv.getExecutable() != null
                    && mutatorNames.contains(inv.getExecutable().getSimpleName());
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
     * {@code true} when the call was folded (its resolvable targets emitted, any
     * unresolvable ones recorded); {@code false} when it cannot be summarised
     * soundly — a library/abstract callee, a non-hierarchy return, an exhausted
     * depth budget, or a recursion cycle — leaving the caller to record it
     * unresolved (the never-guess invariant).
     */
    private boolean resolveInterprocedural(CtInvocation<?> inv, String from, String event,
                                           String guard, Set<Transition> out) {
        CtExecutableReference<?> exe = inv.getExecutable();
        if (exe == null) return false;
        CtTypeReference<?> ret = exe.getType();
        if (ret == null || !hierarchyQualifiedNames.contains(ret.getQualifiedName())) {
            return false; // not a state-producing call
        }
        if (!(exe.getExecutableDeclaration() instanceof CtMethod<?> callee) || callee.getBody() == null) {
            return false; // library / abstract / unavailable in the model
        }
        String sig = callee.getSignature();
        if (interProcStack.size() >= MAX_INTERPROC_DEPTH || interProcStack.contains(sig)) {
            return false; // depth budget exhausted or recursion cycle
        }
        List<GuardedExpr> returns = collectReturns(callee.getBody(), null);
        if (returns.isEmpty()) return false; // nothing summarisable (e.g. returns hidden in a switch)

        boolean top = interProcStack.isEmpty();
        int resolvedBefore = top ? countResolved(out) : 0;
        interProcStack.push(sig);
        try {
            for (GuardedExpr ge : returns) {
                // Resolve each callee return in the *caller's* from-context, so a
                // returned root-typed value ("the current state") becomes a
                // self-loop to the caller's from-state — exactly as in a direct
                // transition method. A returned call recurses under the budget.
                handleValue(ge.expr(), from, event, merge(guard, ge.guard()), out);
            }
        } finally {
            interProcStack.pop();
            if (top) interProcResolvedEdges += Math.max(0, countResolved(out) - resolvedBefore);
        }
        return true;
    }

    /** Returned / yielded expressions of a body, each with its accumulated guard. */
    private record GuardedExpr(CtExpression<?> expr, String guard) {}

    private List<GuardedExpr> collectReturns(CtElement node, String guard) {
        List<GuardedExpr> out = new ArrayList<>();
        collectReturnsInto(node, guard, out);
        return out;
    }

    private void collectReturnsInto(CtElement node, String guard, List<GuardedExpr> out) {
        if (node == null) return;
        if (node instanceof CtBlock<?> b) {
            for (CtStatement s : b.getStatements()) collectReturnsInto(s, guard, out);
        } else if (node instanceof CtIf ctIf) {
            String c = safeText(ctIf.getCondition());
            collectReturnsInto(ctIf.getThenStatement(), merge(guard, c), out);
            collectReturnsInto(ctIf.getElseStatement(), merge(guard, negate(c)), out);
        } else if (node instanceof CtReturn<?> r && r.getReturnedExpression() != null) {
            out.add(new GuardedExpr(r.getReturnedExpression(), guard));
        } else if (node instanceof CtYieldStatement ys && ys.getExpression() != null) {
            out.add(new GuardedExpr(ys.getExpression(), guard));
        }
        // A helper whose returns hide inside a switch / loop / try is not
        // summarised here; resolveInterprocedural then reports the call unresolved.
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
            String cond = safeText(ctIf.getCondition());
            walk(ctIf.getThenStatement(), from, event, merge(guard, cond), out);
            walk(ctIf.getElseStatement(), from, event, merge(guard, negate(cond)), out);
        } else if (node instanceof CtSwitch<?> sw) {
            walkSwitch(sw, from, event, guard, out);
        } else if (node instanceof CtReturn<?> ret) {
            handleValue(ret.getReturnedExpression(), from, event, guard, out);
        } else if (node instanceof CtYieldStatement ys) {
            handleValue(ys.getExpression(), from, event, guard, out);
        } else if (node instanceof CtAssignment<?, ?> asg) {
            // F2: an assignment to the *state field* is a transition site; the RHS
            // is the next-state expression (`this.state = new Locked();`). A bare
            // assignment to any other variable is a local mutation — read directly
            // by the F1 reaching-definitions pass — and not a produced value here.
            if (mutationMode && isStateFieldWrite(asg.getAssigned())) {
                handleValue(asg.getAssignment(), from, event, guard, out);
            }
        } else if (node instanceof CtInvocation<?> inv && mutationMode && isMutatorCall(inv)) {
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
        } else if (node instanceof CtExpression<?> expr) {
            // arrow-arm expression body: case X -> new A();  (statement is itself
            // an expression) or  case X -> switch (event) { ... };
            handleValue(expr, from, event, guard, out);
        }
        // local-variable declarations and other plain statements do not directly
        // produce a next state (reassigned locals are a scope line), so they are
        // intentionally not descended for value production.
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
        for (CtStatement st : block.getStatements()) {
            walk(st, from, event, acc, out);
            if (st instanceof CtIf ctIf
                    && ctIf.getElseStatement() == null
                    && alwaysTerminates(ctIf.getThenStatement())) {
                acc = merge(acc, negate(safeText(ctIf.getCondition())));
            } else if (alwaysTerminates(st)) {
                break; // remaining statements are unreachable
            }
        }
    }

    /**
     * Best-effort: does control leaving {@code st} never fall through to the next
     * statement? Used only to sharpen guards, so it is deliberately conservative
     * (returns {@code false} when unsure).
     */
    private static boolean alwaysTerminates(CtStatement st) {
        if (st instanceof CtReturn<?> || st instanceof CtYieldStatement) {
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
        return false;
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
     */
    private boolean isReassignedLocal(CtVariableAccess<?> va) {
        CtVariableReference<?> vref = va.getVariable();
        if (vref == null || !(vref.getDeclaration() instanceof CtLocalVariable<?>)) {
            return false;
        }
        return TransitionResolver.isReassigned(vref);
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
     */
    private void walkSwitch(CtAbstractSwitch<?> sw, String from, String event,
                            String guard, Set<Transition> out) {
        boolean overState = isStateDispatch(sw);
        boolean overEvent = !overState && isEventDispatch(sw);
        for (CtCase<?> c : sw.getCases()) {
            String caseFrom = from;
            String caseEvent = event;
            if (overState) {
                caseFrom = caseFromState(c);
                if (caseFrom == null) {
                    diagnostics.add("could not determine source state for a switch case: "
                            + truncate(safeText(c)));
                    // fall through with a null from so produced targets are still
                    // recorded (as undetermined-origin) rather than dropped.
                }
            } else if (overEvent) {
                String ev = caseEventName(c);
                if (ev != null) caseEvent = ev; // default/unmatched arm keeps inherited event
            }
            String caseGuard = merge(guard, caseGuard(c));
            for (CtStatement st : c.getStatements()) {
                walk(st, caseFrom, caseEvent, caseGuard, out);
            }
        }
    }

    private void emit(String from, String event, String guard,
                      TransitionResolver.Candidate cand, Set<Transition> out) {
        if (cand.resolved()) {
            if (from == null) {
                out.add(Transition.unresolved("<entry>", event, guard,
                        "target " + cand.targetSimpleName() + " with undetermined source state"));
            } else {
                out.add(Transition.resolved(from, cand.targetSimpleName(), event, guard));
            }
        } else {
            out.add(Transition.unresolved(from == null ? "<unknown>" : from, event, guard, cand.raw()));
        }
    }

    // ---- Spoon-version-sensitive accessors (all guarded) ---------------------

    private boolean containsStateDispatchSwitch(CtMethod<?> method) {
        for (CtSwitch<?> sw : method.getElements(new TypeFilter<>(CtSwitch.class))) {
            if (isStateDispatch(sw)) return true;
        }
        for (CtSwitchExpression<?, ?> sw : method.getElements(new TypeFilter<>(CtSwitchExpression.class))) {
            if (isStateDispatch(sw)) return true;
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
                    return t.getSimpleName();
                }
            }
        } catch (Throwable ignored) {
            // fall through to null -> diagnostic
        }
        return null;
    }

    /**
     * Pull a type out of a case label that is a type pattern. Spoon represents
     * these as {@code CtCasePattern} wrapping a {@code CtTypePattern}; the exact
     * accessor names have shifted across versions, so this is fully reflective
     * and best-effort.
     */
    private CtTypeReference<?> patternType(Object caseExpr) {
        try {
            Object pattern = caseExpr;
            // CtCasePattern -> getPattern()
            var getPattern = tryMethod(pattern, "getPattern");
            if (getPattern != null) pattern = getPattern;
            // CtTypePattern -> getVariable().getType(), or getType()
            Object variable = tryMethod(pattern, "getVariable");
            if (variable != null) {
                Object type = tryMethod(variable, "getType");
                if (type instanceof CtTypeReference<?> ref) return ref;
            }
            Object directType = tryMethod(pattern, "getType");
            if (directType instanceof CtTypeReference<?> ref) return ref;
        } catch (Throwable ignored) {
            // best effort
        }
        return null;
    }

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
     * The event matched by a switch-over-event arm (F4): the type pattern for a
     * sealed event ({@code case Lock l ->}) or the constant name for an enum
     * event ({@code case LOCK ->}). Returns {@code null} for a {@code default}
     * arm, so the inherited event label is kept.
     */
    private String caseEventName(CtCase<?> c) {
        try {
            for (CtExpression<?> ce : c.getCaseExpressions()) {
                CtTypeReference<?> t = patternType(ce);
                if (t != null && eventQualifiedNames.contains(t.getQualifiedName())) {
                    return t.getSimpleName();
                }
                // enum constant label: `case LOCK ->` reads the constant.
                if (ce instanceof CtVariableAccess<?> va && va.getVariable() != null) {
                    return va.getVariable().getSimpleName();
                }
            }
        } catch (Throwable ignored) {
            // best effort — fall through to null (keep inherited event)
        }
        return null;
    }

    /** Guarded-pattern {@code when} clause, if present and supported. */
    private String caseGuard(CtCase<?> c) {
        Object guard = tryMethod(c, "getGuard");
        return guard == null ? null : safeText(guard);
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

    private static String eventName(CtMethod<?> method) {
        String name = method.getSimpleName();
        return NEUTRAL_METHOD_NAMES.contains(name.toLowerCase()) ? null : name;
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

    private static String safeText(Object e) {
        if (e == null) return "";
        try {
            return e.toString();
        } catch (Throwable t) {
            return e.getClass().getSimpleName();
        }
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }
}
