package io.sealfsm.extract;

import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.model.Transition;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
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

    private final TransitionResolver resolver;
    private final Set<String> hierarchyQualifiedNames;
    private final List<String> diagnostics = new ArrayList<>();

    public TransitionExtractor(Set<String> hierarchyQualifiedNames, String rootQualifiedName) {
        this.hierarchyQualifiedNames = hierarchyQualifiedNames;
        this.resolver = new TransitionResolver(hierarchyQualifiedNames, rootQualifiedName);
    }

    public List<String> diagnostics() {
        return diagnostics;
    }

    public List<Transition> extract(CtType<?> root, spoon.reflect.CtModel model) {
        Set<Transition> out = new LinkedHashSet<>();
        for (CtMethod<?> m : StateMachineClassifier.findDistributedTransitionMethods(root)) {
            extractDistributed(m, out);
        }
        for (CtMethod<?> m : StateMachineClassifier.findCentralizedTransitionMethods(root, model)) {
            extractCentralized(m, out);
        }
        return new ArrayList<>(out);
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
        if (!containsStateDispatchSwitch(method)) {
            // No switch we could attribute from-states to. Walk anyway so produced
            // targets remain visible (recorded with an undetermined origin), and
            // flag for manual review.
            diagnostics.add("centralized method '" + method.getSignature()
                    + "' has no recognised switch over the state type; "
                    + "from-states could not be attributed");
        }
        // Centralized-style event labelling is a v1 scope line, so event is null;
        // from starts unknown and is set per matched state-pattern case.
        walk(method.getBody(), null, null, null, out);
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
        } else if (node instanceof CtExpression<?> expr) {
            // arrow-arm expression body: case X -> new A();  (statement is itself
            // an expression) or  case X -> switch (event) { ... };
            handleValue(expr, from, event, guard, out);
        }
        // loops, try/catch, local-variable declarations and plain statements do
        // not directly produce a next state (reassigned locals are a scope line),
        // so they are intentionally not descended for value production.
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
        for (TransitionResolver.Candidate cand : resolver.resolve(value, from)) {
            emit(from, event, merge(guard, cand.guard()), cand, out);
        }
    }

    /**
     * Walk the arms of a switch. When the switch dispatches on the state type,
     * each arm's from-state is the matched type pattern; otherwise the from-state
     * is inherited (e.g. a switch-over-event inside a distributed method or
     * inside a state arm).
     */
    private void walkSwitch(CtAbstractSwitch<?> sw, String from, String event,
                            String guard, Set<Transition> out) {
        boolean overState = isStateDispatch(sw);
        for (CtCase<?> c : sw.getCases()) {
            String caseFrom;
            if (overState) {
                caseFrom = caseFromState(c);
                if (caseFrom == null) {
                    diagnostics.add("could not determine source state for a switch case: "
                            + truncate(safeText(c)));
                    // fall through with a null from so produced targets are still
                    // recorded (as undetermined-origin) rather than dropped.
                }
            } else {
                caseFrom = from;
            }
            String caseGuard = merge(guard, caseGuard(c));
            for (CtStatement st : c.getStatements()) {
                walk(st, caseFrom, event, caseGuard, out);
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

    /** Guarded-pattern {@code when} clause, if present and supported. */
    private String caseGuard(CtCase<?> c) {
        Object guard = tryMethod(c, "getGuard");
        return guard == null ? null : safeText(guard);
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
