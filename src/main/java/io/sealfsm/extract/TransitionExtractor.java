package io.sealfsm.extract;

import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.model.Transition;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtStatement;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtYieldStatement;
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
 * method's returned expressions.
 *
 * <p><b>Centralized</b>: a single function switches over the current state
 * (type patterns) and returns the next. The <em>from</em>-state is the matched
 * pattern type; the <em>to</em>-state(s) come from the matching arm.
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
        String from = declaring.getSimpleName();
        String event = eventName(method);

        List<CtReturn<?>> returns = method.getElements(new TypeFilter<>(CtReturn.class));
        for (CtReturn<?> ret : returns) {
            CtExpression<?> expr = ret.getReturnedExpression();
            if (expr instanceof CtSwitchExpression<?, ?> sw) {
                // State next() { return switch (event) { ... }; }
                extractFromSwitchArms(sw, from, /*fromFixed=*/true, out);
            } else {
                addCandidates(from, event, expr, out);
            }
        }
    }

    // ---- centralized (single transition function) ----------------------------

    private void extractCentralized(CtMethod<?> method, Set<Transition> out) {
        boolean dispatchedOnState = false;

        // Assign through explicitly typed locals so the generic element type is
        // inferred cleanly (avoids raw-type friction when merging the two kinds).
        List<CtSwitch<?>> statementSwitches =
                method.getElements(new TypeFilter<>(CtSwitch.class));
        List<CtSwitchExpression<?, ?>> expressionSwitches =
                method.getElements(new TypeFilter<>(CtSwitchExpression.class));

        for (CtSwitch<?> sw : statementSwitches) {
            if (handleStateDispatchSwitch(sw, out)) dispatchedOnState = true;
        }
        for (CtSwitchExpression<?, ?> sw : expressionSwitches) {
            if (handleStateDispatchSwitch(sw, out)) dispatchedOnState = true;
        }

        if (!dispatchedOnState) {
            // No state-dispatch switch we could attribute from-states to; record
            // every produced state as an unresolved-origin transition so it is
            // still visible, and flag for manual review.
            diagnostics.add("centralized method '" + method.getSignature()
                    + "' has no recognised switch over the state type; "
                    + "from-states could not be attributed");
            List<CtReturn<?>> returns = method.getElements(new TypeFilter<>(CtReturn.class));
            for (CtReturn<?> ret : returns) {
                for (TransitionResolver.Candidate c : resolver.resolve(ret.getReturnedExpression(), null)) {
                    if (c.resolved()) {
                        out.add(Transition.unresolved("<entry>", null, c.guard(),
                                "target " + c.targetSimpleName() + " with undetermined source state"));
                    }
                }
            }
        }
    }

    /** Returns true if {@code sw} switches over the state type and was handled. */
    private boolean handleStateDispatchSwitch(CtAbstractSwitch<?> sw, Set<Transition> out) {
        CtTypeReference<?> selType = selectorType(sw);
        boolean overState = selType != null
                && hierarchyQualifiedNames.contains(selType.getQualifiedName());
        if (!overState) return false;
        extractFromSwitchArms(sw, /*fixedFrom=*/null, /*fromFixed=*/false, out);
        return true;
    }

    // ---- shared switch-arm handling ------------------------------------------

    private void extractFromSwitchArms(CtAbstractSwitch<?> sw, String fixedFrom,
                                       boolean fromFixed, Set<Transition> out) {
        for (CtCase<?> c : sw.getCases()) {
            String from = fromFixed ? fixedFrom : caseFromState(c);
            String guard = caseGuard(c);
            if (from == null) {
                diagnostics.add("could not determine source state for a switch case: "
                        + truncate(safeText(c)));
                // still record produced targets as unresolved-origin
                for (CtExpression<?> value : caseValueExpressions(c)) {
                    for (TransitionResolver.Candidate cand : resolver.resolve(value, null)) {
                        if (cand.resolved()) {
                            out.add(Transition.unresolved("<unknown>", null,
                                    merge(guard, cand.guard()),
                                    "target " + cand.targetSimpleName()));
                        }
                    }
                }
                continue;
            }
            for (CtExpression<?> value : caseValueExpressions(c)) {
                for (TransitionResolver.Candidate cand : resolver.resolve(value, from)) {
                    emit(from, null, merge(guard, cand.guard()), cand, out);
                }
            }
        }
    }

    private void addCandidates(String from, String event, CtExpression<?> expr, Set<Transition> out) {
        for (TransitionResolver.Candidate cand : resolver.resolve(expr, from)) {
            emit(from, event, cand.guard(), cand, out);
        }
    }

    private void emit(String from, String event, String guard,
                      TransitionResolver.Candidate cand, Set<Transition> out) {
        if (cand.resolved()) {
            out.add(Transition.resolved(from, cand.targetSimpleName(), event, guard));
        } else {
            out.add(Transition.unresolved(from, event, guard, cand.raw()));
        }
    }

    // ---- Spoon-version-sensitive accessors (all guarded) ---------------------

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

    /** Collect the value-producing expressions inside a case arm. */
    private List<CtExpression<?>> caseValueExpressions(CtCase<?> c) {
        List<CtExpression<?>> values = new ArrayList<>();
        for (CtStatement st : c.getStatements()) {
            collectValues(st, values);
        }
        return values;
    }

    private void collectValues(CtStatement st, List<CtExpression<?>> values) {
        if (st instanceof CtBlock<?> block) {
            for (CtStatement inner : block.getStatements()) collectValues(inner, values);
        } else if (st instanceof CtYieldStatement ys) {
            if (ys.getExpression() != null) values.add(ys.getExpression());
        } else if (st instanceof CtReturn<?> ret) {
            if (ret.getReturnedExpression() != null) values.add(ret.getReturnedExpression());
        } else if (st instanceof CtExpression<?> expr) {
            // arrow form: case X -> new A();  (the statement is itself an expression)
            values.add(expr);
        }
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
