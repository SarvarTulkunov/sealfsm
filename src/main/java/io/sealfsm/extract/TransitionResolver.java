package io.sealfsm.extract;

import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Resolves a single returned/produced expression to the concrete target
 * state(s) it yields. This is the intra-procedural data-flow core of transition
 * extraction.
 *
 * <p>Handled precisely:
 * <ul>
 *   <li>{@code new Green()} — direct construction.</li>
 *   <li>{@code this} / {@code return this} — self-loop.</li>
 *   <li>{@code cond ? a : b} — two guarded transitions, with the condition
 *       text becoming the SCXML {@code cond}.</li>
 *   <li>a read of a field/variable whose declared type is a concrete state, or
 *       whose initializer constructs one — state singletons.</li>
 * </ul>
 *
 * <p>Deliberately left unresolved (recorded, not chased — the v1 scope line):
 * targets produced by another method call ({@code return helper()}), or by a
 * local that is reassigned along the control-flow path. These surface as
 * {@link Candidate#resolved() unresolved} candidates so they appear in the
 * output and depress recall honestly rather than vanishing.
 */
public final class TransitionResolver {

    /** A possible transition target derived from one expression. */
    public record Candidate(String targetSimpleName, String guard, boolean resolved, String raw) {
        static Candidate of(String target, String guard) {
            return new Candidate(target, guard, true, null);
        }
        static Candidate unresolved(String guard, String raw) {
            return new Candidate(null, guard, false, raw);
        }
    }

    private final Set<String> hierarchyQualifiedNames;
    private final String rootQualifiedName;

    public TransitionResolver(Set<String> hierarchyQualifiedNames, String rootQualifiedName) {
        this.hierarchyQualifiedNames = hierarchyQualifiedNames;
        this.rootQualifiedName = rootQualifiedName;
    }

    public List<Candidate> resolve(CtExpression<?> expr, String fromSimpleName) {
        return resolve(expr, fromSimpleName, null);
    }

    private List<Candidate> resolve(CtExpression<?> expr, String fromSimpleName, String guard) {
        List<Candidate> out = new ArrayList<>();
        if (expr == null) {
            return out;
        }

        // (State) x  ->  an explicit cast to a *concrete* state pins the target,
        // overriding whatever the underlying expression's static type would say
        // (e.g. `return (Locked) current;` where `current` is typed as the root).
        // Spoon attaches casts to the expression itself rather than a wrapper node.
        Candidate viaCast = fromCast(expr, guard);
        if (viaCast != null) {
            out.add(viaCast);
            return out;
        }

        // new Green()
        if (expr instanceof CtConstructorCall<?> cc) {
            out.add(fromTypeRef(cc.getType(), guard, expr));
            return out;
        }

        // return this  ->  self-loop
        if (expr instanceof CtThisAccess<?> && fromSimpleName != null) {
            out.add(Candidate.of(fromSimpleName, guard));
            return out;
        }

        // cond ? a : b  ->  guarded split
        if (expr instanceof CtConditional<?> cond) {
            String condText = safeText(cond.getCondition());
            out.addAll(resolve(cond.getThenExpression(), fromSimpleName, combine(guard, condText)));
            out.addAll(resolve(cond.getElseExpression(), fromSimpleName, combine(guard, negate(condText))));
            return out;
        }

        // field / variable / parameter read
        if (expr instanceof CtVariableAccess<?> va) {
            Candidate c = fromVariable(va, guard, expr, fromSimpleName);
            if (c != null) {
                out.add(c);
                return out;
            }
        }

        // return helper(...)  -> inter-procedural; out of v1 scope
        if (expr instanceof CtInvocation<?> inv) {
            out.add(Candidate.unresolved(guard, safeText(inv)));
            return out;
        }

        // anything else
        out.add(Candidate.unresolved(guard, safeText(expr)));
        return out;
    }

    /**
     * If {@code expr} carries an explicit cast to a concrete state (not the
     * abstract root), that cast determines the target. Returns {@code null} when
     * there is no such cast, so the caller falls through to normal resolution.
     */
    private Candidate fromCast(CtExpression<?> expr, String guard) {
        List<CtTypeReference<?>> casts = expr.getTypeCasts();
        if (casts == null) return null;
        for (CtTypeReference<?> cast : casts) {
            if (cast == null) continue;
            String q = cast.getQualifiedName();
            if (!q.equals(rootQualifiedName) && hierarchyQualifiedNames.contains(q)) {
                return Candidate.of(cast.getSimpleName(), guard);
            }
        }
        return null;
    }

    private Candidate fromTypeRef(CtTypeReference<?> ref, String guard, CtExpression<?> raw) {
        if (ref != null && hierarchyQualifiedNames.contains(ref.getQualifiedName())) {
            return Candidate.of(ref.getSimpleName(), guard);
        }
        return Candidate.unresolved(guard, safeText(raw));
    }

    private Candidate fromVariable(CtVariableAccess<?> va, String guard, CtExpression<?> raw, String fromSimpleName) {
        CtVariableReference<?> vref = va.getVariable();
        if (vref == null) return null;

        // (a) declared type tells us what the read yields
        CtTypeReference<?> declaredType = vref.getType();
        if (declaredType != null) {
            String dq = declaredType.getQualifiedName();
            // A read of a variable typed as the *abstract root* (typically the
            // `current` selector parameter, e.g. `return current;`) means "stay
            // in the matched state" -> self-loop to the from-state.
            if (dq.equals(rootQualifiedName)) {
                // The "root-typed read means stay in the matched state" shortcut is
                // sound only for the *unmodified* selector (e.g. `current`). A
                // reassigned variable would fabricate a proven self-loop, so it is
                // left unresolved here; reassigned *locals* are recovered upstream
                // by the extractor's reaching-definitions pass (see F1).
                if (fromSimpleName != null && !isReassigned(vref)) {
                    return Candidate.of(fromSimpleName, guard);
                }
                return Candidate.unresolved(guard, safeText(raw));
            }
            // A read of a variable typed as a *concrete* state resolves to it.
            if (hierarchyQualifiedNames.contains(dq)) {
                return Candidate.of(declaredType.getSimpleName(), guard);
            }
        }

        // (b) initializer constructs a state: static final Green G = new Green();
        CtVariable<?> decl = vref.getDeclaration();
        if (decl != null && decl.getDefaultExpression() instanceof CtConstructorCall<?> cc) {
            CtTypeReference<?> t = cc.getType();
            if (t != null && hierarchyQualifiedNames.contains(t.getQualifiedName())) {
                return Candidate.of(t.getSimpleName(), guard);
            }
        }
        return Candidate.unresolved(guard, safeText(raw));
    }

    /**
     * True when the variable named by {@code vref} is written by some assignment
     * in its declaring method. Keeps the "root-typed read → self-loop" shortcut
     * restricted to the <em>unmodified</em> selector: a reassigned variable would
     * otherwise be reported as a false, proven self-loop (see finding F1). This is
     * package-visible so the extractor's reaching-definitions pass reuses the same
     * notion of "reassigned".
     */
    static boolean isReassigned(CtVariableReference<?> vref) {
        if (vref == null) return false;
        CtVariable<?> decl = vref.getDeclaration();
        if (decl == null) return false;
        CtMethod<?> method = decl.getParent(CtMethod.class);
        if (method == null) return false;
        String name = vref.getSimpleName();
        for (CtAssignment<?, ?> a : method.getElements(new TypeFilter<>(CtAssignment.class))) {
            CtExpression<?> lhs = a.getAssigned();
            if (lhs instanceof CtVariableAccess<?> vw
                    && !(lhs instanceof CtFieldAccess<?>)
                    && vw.getVariable() != null
                    && name.equals(vw.getVariable().getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private static String combine(String existing, String added) {
        if (added == null || added.isBlank()) return existing;
        if (existing == null || existing.isBlank()) return added;
        return existing + " && " + added;
    }

    private static String negate(String condText) {
        if (condText == null || condText.isBlank()) return null;
        return "!(" + condText + ")";
    }

    private static String safeText(CtExpression<?> e) {
        if (e == null) return "";
        try {
            return e.toString();
        } catch (Throwable t) {
            return e.getClass().getSimpleName();
        }
    }
}
