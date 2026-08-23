package io.sealfsm.extract;

import io.sealfsm.model.StateNaming;
import io.sealfsm.model.SuccessorForm;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.HashSet;
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
    public record Candidate(String targetSimpleName, String guard, boolean resolved,
                            String raw, SuccessorForm form) {
        static Candidate of(String target, String guard, SuccessorForm form) {
            return new Candidate(target, guard, true, null, form);
        }
        static Candidate unresolved(String guard, String raw) {
            return new Candidate(null, guard, false, raw, null);
        }
        /** The same candidate re-labelled with the form that reached it. */
        Candidate as(SuccessorForm f) {
            return resolved ? new Candidate(targetSimpleName, guard, true, raw, f) : this;
        }
    }

    /**
     * Depth bound for chasing a variable through its initializer. Two hops covers
     * {@code H a = SINGLETON;} → {@code static final H SINGLETON = new Idle();}
     * while keeping a self-referential or mutually-referential pair terminating;
     * the {@code seen} set is the real cycle guard and this is the belt-and-braces.
     */
    private static final int MAX_INITIALIZER_DEPTH = 4;

    private final Set<String> hierarchyQualifiedNames;
    private final String rootQualifiedName;
    /**
     * Maps a resolved target type to the id the state carries. Never bypassed with
     * a bare {@code getSimpleName()}: two permitted subtypes may share a simple
     * name, and a target spelled that way would name whichever of them the
     * serializer happened to declare first.
     */
    private final StateNaming naming;

    public TransitionResolver(Set<String> hierarchyQualifiedNames, String rootQualifiedName) {
        this(hierarchyQualifiedNames, rootQualifiedName, StateNaming.EMPTY);
    }

    public TransitionResolver(Set<String> hierarchyQualifiedNames, String rootQualifiedName,
                              StateNaming naming) {
        this.hierarchyQualifiedNames = hierarchyQualifiedNames;
        this.rootQualifiedName = rootQualifiedName;
        this.naming = naming == null ? StateNaming.EMPTY : naming;
    }

    public List<Candidate> resolve(CtExpression<?> expr, String fromSimpleName) {
        return resolve(expr, fromSimpleName, null, new HashSet<>(), 0);
    }

    /**
     * Map one produced expression to the permitted subtype(s) it can denote.
     *
     * <p>Every hierarchy-typed form is handled here rather than at the call sites,
     * so the same sub-procedure serves centralized and per-state dispatch alike:
     * construction, {@code this}, an explicit cast, a singleton field, an enum
     * constant, and a local whose value is fixed within this method. Anything whose
     * identity would require leaving the method — a parameter that is not the
     * dispatch selector, a helper call — yields an <em>unresolved</em> candidate.
     */
    private List<Candidate> resolve(CtExpression<?> expr, String fromSimpleName, String guard,
                                    Set<CtElement> seen, int depth) {
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
        if (expr instanceof CtThisAccess<?>) {
            out.add(fromSimpleName != null
                    ? Candidate.of(fromSimpleName, guard, SuccessorForm.SELF)
                    : Candidate.unresolved(guard, safeText(expr)));
            return out;
        }

        // cond ? a : b  ->  guarded split
        if (expr instanceof CtConditional<?> cond) {
            String condText = safeText(cond.getCondition());
            out.addAll(resolve(cond.getThenExpression(), fromSimpleName,
                    combine(guard, condText), seen, depth));
            out.addAll(resolve(cond.getElseExpression(), fromSimpleName,
                    combine(guard, negate(condText)), seen, depth));
            return out;
        }

        // field / variable / parameter read
        if (expr instanceof CtVariableAccess<?> va) {
            out.addAll(fromVariable(va, guard, expr, fromSimpleName, seen, depth));
            return out;
        }

        // return helper(...)  -> the successor is computed elsewhere. The extractor
        // may fold a bounded summary (F3); on its own this stays unresolved.
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
                return Candidate.of(naming.idFor(q), guard, SuccessorForm.CAST);
            }
        }
        return null;
    }

    private Candidate fromTypeRef(CtTypeReference<?> ref, String guard, CtExpression<?> raw) {
        if (ref != null && hierarchyQualifiedNames.contains(ref.getQualifiedName())) {
            return Candidate.of(naming.idFor(ref.getQualifiedName()), guard, SuccessorForm.CONSTRUCTION);
        }
        return Candidate.unresolved(guard, safeText(raw));
    }

    /**
     * Resolve a read of a field, local, parameter or pattern binding to the state
     * it denotes. The order matters and encodes what can actually be <em>proved</em>:
     *
     * <ol>
     *   <li><b>Enum constant</b> of a permitted {@code enum} subtype — the constant
     *       <em>is</em> the state, so its identity beats its declared type (which
     *       is merely the enum).</li>
     *   <li><b>Initializer</b> — a variable bound once to a hierarchy value denotes
     *       that value: {@code static final Signal INSTANCE = new Idle();}. This
     *       must precede the declared-type rules, because a singleton is routinely
     *       declared as the abstract root and rule (4) would otherwise mistake it
     *       for the current state.</li>
     *   <li><b>Concrete declared type</b> — a variable typed as a permitted subtype
     *       can only hold that subtype.</li>
     *   <li><b>Root-typed selector</b> — a parameter or pattern binding typed as the
     *       abstract root is the value being dispatched on, so reading it means
     *       "stay in the matched state".</li>
     * </ol>
     *
     * A root-typed local or field that survives all four cannot be pinned without
     * leaving the method, and is reported unresolved rather than guessed.
     */
    private List<Candidate> fromVariable(CtVariableAccess<?> va, String guard, CtExpression<?> raw,
                                         String fromSimpleName, Set<CtElement> seen, int depth) {
        CtVariableReference<?> vref = va.getVariable();
        if (vref == null) return List.of(Candidate.unresolved(guard, safeText(raw)));

        // (1) enum-constant identity: `return Phase.RAMP;`
        String constant = enumConstantState(vref);
        if (constant != null) {
            return List.of(Candidate.of(constant, guard, SuccessorForm.ENUM_CONSTANT));
        }

        CtVariable<?> decl = vref.getDeclaration();
        CtTypeReference<?> declaredType = vref.getType();
        String dq = declaredType == null ? null : declaredType.getQualifiedName();

        // (2) the initializer fixes the value. Resolved recursively so a singleton
        // pointing at another singleton, a cast or a ternary all work; `seen`
        // stops a self- or mutually-referential declaration from looping.
        if (decl != null && !isReassigned(vref) && depth < MAX_INITIALIZER_DEPTH && seen.add(decl)) {
            CtExpression<?> init = decl.getDefaultExpression();
            if (init != null && !(init instanceof CtVariableAccess<?> self && refersTo(self, vref))) {
                List<Candidate> viaInit = resolve(init, fromSimpleName, guard, seen, depth + 1);
                if (viaInit.stream().allMatch(Candidate::resolved) && !viaInit.isEmpty()) {
                    SuccessorForm form = decl instanceof CtField<?>
                            ? SuccessorForm.SINGLETON_FIELD
                            : SuccessorForm.LOCAL_VARIABLE;
                    // A `this`-initialised or selector-initialised binding is still a
                    // self-loop; keep SELF rather than relabelling it a singleton.
                    return viaInit.stream()
                            .map(c -> c.form() == SuccessorForm.SELF ? c : c.as(form))
                            .toList();
                }
            }
        }

        if (dq != null) {
            // (3) a variable typed as a *concrete* state resolves to it.
            if (!dq.equals(rootQualifiedName) && hierarchyQualifiedNames.contains(dq)) {
                SuccessorForm form = decl instanceof CtField<?>
                        ? SuccessorForm.SINGLETON_FIELD
                        : SuccessorForm.LOCAL_VARIABLE;
                return List.of(Candidate.of(naming.idFor(dq), guard, form));
            }
            // (4) a root-typed *selector* — a parameter, or a pattern binding —
            // means "stay in the matched state". Deliberately NOT applied to a
            // local or field: those hold a value that has an identity of its own,
            // and reading the declared type there would fabricate a self-loop
            // (finding F1). Reassigned locals are recovered upstream by the
            // extractor's reaching-definitions pass.
            if (dq.equals(rootQualifiedName)
                    && fromSimpleName != null
                    && isSelectorBinding(decl)
                    && !isReassigned(vref)) {
                return List.of(Candidate.of(fromSimpleName, guard, SuccessorForm.SELF));
            }
        }
        return List.of(Candidate.unresolved(guard, safeText(raw)));
    }

    /**
     * The state named by a read of an {@code enum} constant belonging to a permitted
     * subtype, or {@code null} when this is not such a read. The constant is the
     * state — {@code StateExtractor} enumerates a permitted enum's constants as its
     * child states, exactly as it does a nested {@code permits} clause — so
     * resolving to the enum type instead would lose the distinction between
     * {@code RAMP} and {@code PEAK}.
     */
    private String enumConstantState(CtVariableReference<?> vref) {
        if (vref.getDeclaration() instanceof CtEnumValue<?> ev) {
            CtType<?> owner = ev.getDeclaringType();
            if (owner != null && hierarchyQualifiedNames.contains(owner.getQualifiedName())) {
                return naming.idForEnumConstant(owner.getQualifiedName(), ev.getSimpleName());
            }
            return null;
        }
        // noClasspath fallback: the declaration may be unavailable, but a field
        // reference still names its owner, and that owner being an enum inside the
        // hierarchy is enough to identify the read as a constant.
        if (vref instanceof CtFieldReference<?> fref) {
            CtTypeReference<?> owner = fref.getDeclaringType();
            if (owner != null && hierarchyQualifiedNames.contains(owner.getQualifiedName())
                    && owner.getTypeDeclaration() instanceof CtEnum<?>) {
                return naming.idForEnumConstant(owner.getQualifiedName(), fref.getSimpleName());
            }
        }
        return null;
    }

    /**
     * Is this declaration the value the enclosing code dispatches on — a method
     * parameter or a pattern binding — as opposed to a local or field holding a
     * successor? Only the former licenses the "root-typed read means stay put"
     * reading.
     */
    private static boolean isSelectorBinding(CtVariable<?> decl) {
        if (decl == null) return true;  // unresolvable declaration: keep prior behaviour
        return !(decl instanceof CtField<?>) && !(decl instanceof CtLocalVariable<?>);
    }

    private static boolean refersTo(CtVariableAccess<?> access, CtVariableReference<?> vref) {
        return access.getVariable() != null
                && access.getVariable().getSimpleName().equals(vref.getSimpleName());
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

    /**
     * Source text of an expression, flattened to one line — the same contract as
     * {@code TransitionExtractor.safeText}. This text becomes an unresolved edge's
     * note and a ternary's guard, both of which reach a DOT label and an SCXML
     * attribute, and a multi-line expression there produces an unreadable
     * multi-line label rather than any visible error.
     */
    private static String safeText(CtExpression<?> e) {
        if (e == null) return "";
        try {
            return e.toString().replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            return e.getClass().getSimpleName();
        }
    }
}
