package io.sealfsm.extract;

import io.sealfsm.detect.dispatch.CompositionVeto;
import io.sealfsm.model.StateNaming;
import io.sealfsm.model.SuccessorForm;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtConditional;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtFieldAccess;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.code.CtSuperAccess;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.code.CtCase;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

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

    /**
     * A possible transition target derived from one expression.
     *
     * @param deferred non-null when the expression could only be read by the
     *                 extractor's own analysis in an OUTER frame (see
     *                 {@link Deferred}). Such a candidate is {@code resolved ==
     *                 false} and carries the unresolved {@code raw} it stands for,
     *                 so any consumer that does not know about deferral records it
     *                 exactly as it did before deferral existed
     * @param via      the binding hops this value travelled, innermost first, for
     *                 {@code --explain}; or why a binding was refused. Descriptive
     *                 only — never consulted by a decision, never part of the output
     */
    public record Candidate(String targetSimpleName, String guard, boolean resolved,
                            String raw, SuccessorForm form, Deferred deferred, List<String> via) {
        public Candidate {
            via = via == null ? List.of() : List.copyOf(via);
        }
        static Candidate of(String target, String guard, SuccessorForm form) {
            return new Candidate(target, guard, true, null, form, null, List.of());
        }
        static Candidate unresolved(String guard, String raw) {
            return new Candidate(null, guard, false, raw, null, null, List.of());
        }
        /** The same candidate re-labelled with the form that reached it. */
        Candidate as(SuccessorForm f) {
            return resolved ? new Candidate(targetSimpleName, guard, true, raw, f, deferred, via) : this;
        }
        /** The same candidate, having travelled one more binding hop. */
        Candidate through(String hop) {
            List<String> v = new ArrayList<>(via.size() + 1);
            v.addAll(via);
            v.add(hop);
            return new Candidate(targetSimpleName, guard, resolved, raw, form, deferred, v);
        }
    }

    /**
     * A bound expression that the pure resolver cannot read and the extractor's
     * analysis can — a call to fold, a switch expression to walk, a reassigned
     * local whose reaching definitions to recover — together with the frame it is
     * WRITTEN in, which is where it must be evaluated.
     *
     * <p>This is the half of F25 that stopped at the resolver's edge. Rule (5)
     * resolved a bound argument by resolving the caller's expression, but it did so
     * with the pure resolver, which never folds: {@code forward(States.armed())}
     * bound {@code s} to {@code States.armed()} and then answered "a call —
     * unresolved", although that exact call, returned directly, folds and
     * resolves. The binding carried the expression; the analysis applied to it was
     * weaker than the one the caller would have applied to itself. Handing it back
     * with its frame lets the caller's own analysis finish the job — the fold
     * claims exactly what the caller would claim had it inlined the callee.
     */
    public record Deferred(CtExpression<?> expression, BindingFrame frame, String hop) { }

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

    /**
     * Variable names for which {@link #isReassigned} could not decide identity and
     * had to fall back to a name match. Drained by {@link TransitionExtractor} into
     * a diagnostic, so a read resolved (or declined) on that weaker basis is
     * reported rather than passing for a proven one.
     */
    private final Set<String> nameOnlyReassignmentChecks = new LinkedHashSet<>();

    public TransitionResolver(Set<String> hierarchyQualifiedNames, String rootQualifiedName) {
        this(hierarchyQualifiedNames, rootQualifiedName, StateNaming.EMPTY);
    }

    public TransitionResolver(Set<String> hierarchyQualifiedNames, String rootQualifiedName,
                              StateNaming naming) {
        this.hierarchyQualifiedNames = hierarchyQualifiedNames;
        this.rootQualifiedName = rootQualifiedName;
        this.naming = naming == null ? StateNaming.EMPTY : naming;
    }

    /** Names whose reassignment status rested on a name match; see the field. */
    Set<String> nameOnlyReassignmentChecks() {
        return nameOnlyReassignmentChecks;
    }

    /**
     * F25/F29 — the fold frame expressions are currently evaluated in: a callee
     * together with each PARAMETER mapped to the expression the caller actually
     * passed at the call site being summarised, the receiver {@code this} denotes,
     * and a link to the frame the call site is written in. {@code null} in the
     * dispatch host. F25 kept this as a list of maps beside a separate stack of
     * signature strings in the extractor; {@link BindingFrame} is now the only fold
     * state there is.
     *
     * <p>The fold walks a callee's body in the caller's from-context, but until
     * this map existed it carried no binding from the callee's parameters to the
     * caller's arguments — so any successor arriving through a parameter read was
     * unresolvable <em>by construction</em>. {@code case Idle i -> wrap(new
     * Running(), List.of())}, folded into {@code Carrier wrap(H s, List&lt;A&gt; a)
     * { return new Carrier(s, a); }}, reached {@code s} with nothing bound to it.
     * The successor {@code new Running()} had been in hand at the call site and
     * <em>entering discarded it</em>: each hop destroyed information rather than
     * recovering it, which is why declining to fold used to score better than
     * folding.
     *
     * <p>Binding a parameter to the expression the caller passed is EXACT — it is
     * the argument, not an approximation of it — so this does not widen what the
     * analysis claims. It stops it discarding what it already had. Two things keep
     * it exact:
     * <ul>
     *   <li>the map is IDENTITY-keyed. Spoon gives {@code CtElement} deep
     *       structural equality, so two distinct helpers' identically-spelled
     *       parameters compare equal and one would stand in for the other. This is
     *       the F13 rule, unchanged;</li>
     *   <li>a parameter REASSIGNED inside the callee is not bound at all. What it
     *       holds at the {@code return} is then no longer the argument, and the
     *       binding would be a claim about a value that has since been overwritten.
     *       {@link #isReassigned} answers it, by declaration identity.</li>
     * </ul>
     *
     * <p>Rule 4's restriction (F24) is now a DERIVED case of this map rather than a
     * second mechanism: see {@link #selectorHoldsCurrentState}. One notion of "what
     * a parameter holds", not two — a second would eventually disagree with the
     * first, and the disagreement would be an edge.
     */
    private BindingFrame frame;

    /**
     * How many folds (and evaluations on a fold's behalf) are in progress,
     * physically. Distinct from {@link #frame}: while a bound argument is being
     * evaluated in the dispatch host, {@code frame} is {@code null} yet the
     * analysis is still inside a fold, and rule 4 must know that (see
     * {@link #selectorHoldsCurrentState}). Zero means no fold is anywhere on the
     * stack, which is the only context in which rule 4 applies as it always did.
     */
    private int activity = 0;
    private final List<BindingFrame> saved = new ArrayList<>();

    /**
     * Switches the walker is currently reading as a discrimination of some value
     * OTHER than the current state (see {@link #selectorCurrentness}). A pattern
     * binding of one of their arms is that other value, so rule 4's exemption for
     * pattern bindings does not cover it. Identity-keyed, the F13 rule.
     */
    private final Set<CtElement> foreignSwitches =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    /** The frame expressions are currently evaluated in; {@code null} for the host. */
    BindingFrame frame() {
        return frame;
    }

    /**
     * Enter {@code f} — a newly opened fold frame, or an outer frame a deferred
     * expression is being evaluated in ({@code null} for the host). Paired with
     * {@link #leave()} in a {@code finally} by the extractor; the frame is the only
     * fold state there is, so nothing else can be left behind.
     */
    void enter(BindingFrame f) {
        saved.add(frame);
        frame = f;
        activity++;
    }

    void leave() {
        if (saved.isEmpty()) return;
        frame = saved.remove(saved.size() - 1);
        activity--;
    }

    /** Is any fold in progress? Test/diagnostic use. */
    boolean inFold() {
        return activity > 0;
    }

    boolean markForeign(CtElement sw) {
        return foreignSwitches.add(sw);
    }

    void unmarkForeign(CtElement sw) {
        foreignSwitches.remove(sw);
    }

    /**
     * The expression the caller bound to {@code decl} in {@code f}, or {@code null}
     * when nothing did — a local or pattern binding, a parameter the callee
     * reassigns, a varargs slot, one past the end of a shorter argument list, a
     * read outside any fold — or when the read is not in the frame's own body.
     *
     * <p>That last clause is the deferred-execution boundary, stated rather than
     * left to fall out of the walk. A parameter read inside a lambda, an anonymous
     * class or a local class of the callee may run after the call has returned, so
     * the argument is no longer guaranteed to be what the parameter holds; and a
     * read belonging to such a body is the body's own, not the callee's. Decided
     * structurally: the read's nearest enclosing executable must BE the frame's
     * callee.
     */
    private CtExpression<?> boundArgument(CtVariable<?> decl, CtElement read, BindingFrame f) {
        if (!(decl instanceof CtParameter<?> p) || f == null) return null;
        if (!ownedBy(read, f)) return null;
        return f.argumentFor(p);
    }

    /** Why {@code decl} carries no binding in {@code f}, for {@code --explain}. */
    private String unboundReason(CtVariable<?> decl, CtElement read, BindingFrame f) {
        if (!(decl instanceof CtParameter<?> p) || f == null) return null;
        CtExecutable<?> declaredIn;
        try {
            declaredIn = p.getParent(CtExecutable.class);
        } catch (Throwable t) {
            declaredIn = null;
        }
        if (!ownedBy(read, f) || (declaredIn != null && declaredIn != f.callee())) {
            return "`" + p.getSimpleName() + "` is read across a deferred-execution boundary "
                    + "(a lambda or a local/anonymous class) and is not bound (DEFERRED_EXECUTION)";
        }
        return f.refusalFor(p);
    }

    /** Is {@code read} written directly in {@code f}'s callee, with no body between? */
    private static boolean ownedBy(CtElement read, BindingFrame f) {
        if (read == null || f == null) return false;
        try {
            return read.getParent(CtExecutable.class) == f.callee();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Is this declaration usable as "the current state" here — the premise rule 4
     * rests on? Always, outside a fold: at a dispatch the parameter or binding IS
     * what the switch or chain tested. Inside one, a PARAMETER qualifies only when
     * the caller demonstrably handed it the current state; anything else rule 4
     * admits (a pattern binding) is discriminated within the callee itself.
     *
     * <p>F24 stated that as a separate set of "selector parameters". It is the same
     * statement read off the current {@link BindingFrame}: the subset whose bound expression
     * satisfies {@link CompositionVeto#isCurrentState}, the shared predicate for
     * the three ways a walk knows the from-state. Asking the map rather than
     * keeping a parallel set is what stops the two drifting.
     */
    private boolean selectorHoldsCurrentState(CtVariable<?> decl, CtVariableAccess<?> read,
                                              BindingFrame f) {
        if (activity == 0) return true;   // no fold in progress: rule 4 as it was
        if (!(decl instanceof CtParameter<?>)) {
            // A pattern binding is discriminated within the callee itself — unless
            // the switch that bound it was shown to discriminate something OTHER
            // than the current state, in which case the binding is that other value.
            return !boundByForeignSwitch(decl);
        }
        if (f == null) {
            // Rule (5) has walked back OUT to the caller's own body to resolve the
            // expression it passed. Rule 4 must not widen there: the fold reached
            // this point only because the callee's parameter was NOT the current
            // state, and "any parameter is the selector" would contradict what was
            // just established — turning `fwd(fallback)` into a proven self-loop on
            // the strength of `fallback` being a parameter. The same predicate
            // answers it, asked of the read rather than of a binding.
            return CompositionVeto.isCurrentState(read);
        }
        CtExpression<?> bound = boundArgument(decl, read, f);
        return bound != null && CompositionVeto.isCurrentState(bound);
    }

    public List<Candidate> resolve(CtExpression<?> expr, String fromSimpleName) {
        // The cycle guard is IDENTITY-keyed — the F13 rule, and F29 found it missing
        // here. Spoon gives CtElement deep structural equality, so `Lamp x` in one
        // forwarder and `Lamp x` in the next compare equal: a HashSet answered "seen"
        // at the second hop of any chain that reuses a parameter name, and rule (5)
        // stopped there. Reusing the name is the ordinary case (`state`, `next`), so
        // F25's transitive binding held only for chains nobody writes.
        Set<CtElement> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return resolve(expr, fromSimpleName, null, seen, 0, frame);
    }

    // ---- what holds the current state (receiver slot, foreign switches) -------

    /**
     * Does {@code e}, written in frame {@code f}, denote the state currently being
     * succeeded? {@code TRUE}/{@code FALSE} when the frames can say, {@code null}
     * when they cannot (a parameter the caller's binding was refused for).
     *
     * <p>This is the question F24 asked of a folded parameter, asked of the two
     * other things that had been ASSUMED to hold the current state inside a fold
     * without anything checking it:
     * <ul>
     *   <li><b>{@code this}.</b> Every {@code this} read as a self-loop to the
     *       caller's from-state, because there was no receiver slot to ask. That
     *       is exact when the caller wrote {@code i.advance()} with {@code i} the
     *       matched state, and a fabrication when it wrote {@code
     *       DEFAULT.advance()} or {@code new Armed().settle()}: the target is the
     *       receiver, not the source.</li>
     *   <li><b>A state switch's selector</b> (see {@link #selectorCurrentness}).</li>
     * </ul>
     * Both now answer from the SAME frames the parameter bindings live in, so there
     * is one notion of "what holds the current state" rather than three.
     */
    private Boolean currentness(CtExpression<?> e, BindingFrame f, int depth) {
        if (e == null || depth > MAX_INITIALIZER_DEPTH + 8) return null;
        if (e instanceof CtThisAccess<?> || e instanceof CtSuperAccess<?>) {
            // In the host, `this` is the current state exactly when it is a state
            // at all — a per-state method's receiver. (A driver's `this` is not
            // hierarchy-typed and never reaches a state-valued position.)
            if (f == null) return Boolean.TRUE;
            if (!ownedBy(e, f) || !isOwnThis(e, f)) return Boolean.FALSE;
            CtExpression<?> receiver = f.receiver();
            return receiver == null ? Boolean.FALSE : currentness(receiver, f.caller(), depth + 1);
        }
        if (f == null) return CompositionVeto.isCurrentState(e) ? Boolean.TRUE : Boolean.FALSE;
        if (e instanceof CtVariableAccess<?> va && va.getVariable() != null) {
            CtVariable<?> decl = va.getVariable().getDeclaration();
            if (decl instanceof CtParameter<?>) {
                CtExpression<?> bound = boundArgument(decl, e, f);
                return bound == null ? null : currentness(bound, f.caller(), depth + 1);
            }
            if (decl != null && !(decl instanceof CtField<?>) && !(decl instanceof CtLocalVariable<?>)
                    && !boundByForeignSwitch(decl)) {
                return CompositionVeto.isCurrentState(e) ? Boolean.TRUE : Boolean.FALSE;
            }
            if (decl instanceof CtLocalVariable<?> && !boundByForeignSwitch(decl)
                    && CompositionVeto.isCurrentState(e)) {
                // a pattern binding Spoon models as a local variable
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    /**
     * Does the switch whose selector is {@code selector}, walked in the current
     * frame, discriminate the CURRENT state? Answered only where a frame can say —
     * the selector is the folded callee's {@code this} or one of its parameters —
     * and {@code null} everywhere else, which leaves the walk exactly as it was.
     *
     * <p>F18 made a state switch inside a folded body context-sensitive: arms for
     * states the caller already excluded are skipped, and the arm labels become the
     * source states. That is right when the switched value IS the current state
     * ({@code escalate(current)}), and wrong when the caller handed the callee some
     * OTHER state: {@code pick(new Armed(), e)} from the {@code Idle} arm, with
     * {@code pick} switching on its parameter, skipped the {@code Armed} arm as
     * "unreachable" and published the {@code Idle} arm's result — a fabricated
     * edge and a dropped one, behind a clean score. The binding is what says which
     * case it is, so the binding is what is asked.
     */
    Boolean selectorCurrentness(CtExpression<?> selector) {
        if (activity == 0 || frame == null || selector == null) return null;
        if (selector instanceof CtThisAccess<?> || selector instanceof CtSuperAccess<?>) {
            return currentness(selector, frame, 0);
        }
        if (selector instanceof CtVariableAccess<?> va && va.getVariable() != null
                && va.getVariable().getDeclaration() instanceof CtParameter<?> p
                && ownedBy(selector, frame)) {
            CtExpression<?> bound = frame.argumentFor(p);
            return bound == null ? null : currentness(bound, frame.caller(), 0);
        }
        return null;
    }

    /** Is {@code decl} a pattern binding of an arm of a switch read as foreign? */
    private boolean boundByForeignSwitch(CtVariable<?> decl) {
        if (foreignSwitches.isEmpty() || decl == null) return false;
        try {
            CtCase<?> arm = decl.getParent(CtCase.class);
            return arm != null && foreignSwitches.contains(arm.getParent());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Is this {@code this} the callee's own receiver, rather than a qualified
     * {@code Outer.this}? The type it denotes must be the callee's declaring type.
     */
    private static boolean isOwnThis(CtExpression<?> thisAccess, BindingFrame f) {
        try {
            if (!(f.callee() instanceof CtMethod<?> m) || m.getDeclaringType() == null) return false;
            CtTypeReference<?> t = thisAccess.getType();
            return t != null && t.getQualifiedName().equals(m.getDeclaringType().getQualifiedName());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * {@code this} inside a folded callee is the RECEIVER the caller wrote, read in
     * the caller's frame — the slot F25 did not have. Outside any fold it is the
     * current state, as it always was.
     */
    private List<Candidate> fromThis(CtExpression<?> thisAccess, String fromSimpleName, String guard,
                                     Set<CtElement> seen, int depth, BindingFrame f) {
        if (f == null) {
            return List.of(fromSimpleName != null
                    ? Candidate.of(fromSimpleName, guard, SuccessorForm.SELF)
                    : Candidate.unresolved(guard, safeText(thisAccess)));
        }
        CtExpression<?> receiver = f.receiver();
        if (!ownedBy(thisAccess, f) || !isOwnThis(thisAccess, f) || receiver == null) {
            return List.of(Candidate.unresolved(guard, safeText(thisAccess))
                    .through("`this` is not the callee's own receiver here "
                            + "(a static callee, a nested body, or a qualified outer `this`)"));
        }
        String hop = "`this` := receiver " + BindingFrame.describe(receiver)
                + " of " + f.describeCallSite();
        if (Boolean.TRUE.equals(currentness(receiver, f.caller(), 0))) {
            return List.of(fromSimpleName != null
                    ? Candidate.of(fromSimpleName, guard, SuccessorForm.SELF).through(hop)
                    : Candidate.unresolved(guard, safeText(thisAccess)));
        }
        if (depth >= MAX_INITIALIZER_DEPTH || !seen.add(thisAccess)) {
            return List.of(Candidate.unresolved(guard, safeText(thisAccess)));
        }
        if (receiver instanceof CtThisAccess<?> || receiver instanceof CtSuperAccess<?>) {
            return fromThis(receiver, fromSimpleName, guard, seen, depth + 1, f.caller()).stream()
                    .map(c -> c.through(hop)).toList();
        }
        List<Candidate> viaReceiver = resolve(receiver, fromSimpleName, guard, seen, depth + 1, f.caller());
        return deferIfCallerCanRead(viaReceiver, receiver, f.caller(), guard, hop);
    }

    /**
     * The pure reading of a bound expression, or — when that reading failed and the
     * expression is one the extractor's analysis reads further than this class does
     * — the expression handed back with its frame (see {@link Deferred}). The
     * choice is made on the expression's SHAPE, exactly as the extractor's
     * {@code handleValue} makes it for a value the caller produced itself, so the
     * bound expression gets precisely the treatment it would have had there.
     */
    private List<Candidate> deferIfCallerCanRead(List<Candidate> pure, CtExpression<?> bound,
                                                 BindingFrame writtenIn, String guard, String hop) {
        boolean allUnresolved = !pure.isEmpty() && pure.stream().noneMatch(Candidate::resolved);
        if (allUnresolved && callerReadsFurther(bound)) {
            String raw = pure.get(0).raw();
            return List.of(new Candidate(null, guard, false, raw, null,
                    new Deferred(bound, writtenIn, hop), List.of()));
        }
        return pure.stream().map(c -> c.through(hop)).toList();
    }

    /**
     * The expression shapes the extractor reads beyond the pure resolver: a call
     * (the inter-procedural fold), a switch expression (the arm walk), and a read of
     * a reassigned local (reaching definitions, F1).
     */
    private static boolean callerReadsFurther(CtExpression<?> e) {
        if (e instanceof CtInvocation<?> || e instanceof CtSwitchExpression<?, ?>) return true;
        if (e instanceof CtVariableAccess<?> va && va.getVariable() != null
                && va.getVariable().getDeclaration() instanceof CtLocalVariable<?>) {
            return isReassigned(va.getVariable());
        }
        return false;
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
                                    Set<CtElement> seen, int depth, BindingFrame frame) {
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

        // return this  ->  self-loop at a dispatch; the RECEIVER inside a fold
        if (expr instanceof CtThisAccess<?>) {
            out.addAll(fromThis(expr, fromSimpleName, guard, seen, depth, frame));
            return out;
        }

        // cond ? a : b  ->  guarded split
        if (expr instanceof CtConditional<?> cond) {
            String condText = safeText(cond.getCondition());
            out.addAll(resolve(cond.getThenExpression(), fromSimpleName,
                    combine(guard, condText), seen, depth, frame));
            out.addAll(resolve(cond.getElseExpression(), fromSimpleName,
                    combine(guard, negate(condText)), seen, depth, frame));
            return out;
        }

        // field / variable / parameter read
        if (expr instanceof CtVariableAccess<?> va) {
            out.addAll(fromVariable(va, guard, expr, fromSimpleName, seen, depth, frame));
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
     *   <li><b>Bound argument</b> (F25) — inside a folded callee, a parameter holds
     *       the expression the caller passed; resolve that, one frame out. Last,
     *       because the four rules above prove the target from the callee's own
     *       text and need no help from the call site.</li>
     * </ol>
     *
     * A root-typed local or field that survives all five cannot be pinned without
     * leaving the method, and is reported unresolved rather than guessed.
     */
    private List<Candidate> fromVariable(CtVariableAccess<?> va, String guard, CtExpression<?> raw,
                                         String fromSimpleName, Set<CtElement> seen, int depth,
                                         BindingFrame frame) {
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
        if (decl != null && !isReassigned(vref, nameOnlyReassignmentChecks::add)
                && depth < MAX_INITIALIZER_DEPTH) {
            CtExpression<?> init = decl.getDefaultExpression();
            // `seen` is marked only once there is genuinely an initializer to
            // descend into. Marking it before the null check made rule (5) below
            // unreachable for every PARAMETER: a parameter has no initializer, so
            // this rule did nothing with it — yet it had already claimed the
            // declaration, and rule (5)'s own `seen.add` then answered false. The
            // cycle guard is about a chase actually being started, not about the
            // declaration having been looked at.
            if (init != null && !(init instanceof CtVariableAccess<?> self && refersTo(self, vref))
                    && seen.add(decl)) {
                List<Candidate> viaInit = resolve(init, fromSimpleName, guard, seen, depth + 1, frame);
                // A DEFERRED reading counts as a reading here: the variable is never
                // reassigned, so it holds exactly its initializer, and an initializer
                // that is a bound call (`H x = p;` with `p` bound to a factory) is read
                // by the caller's own analysis — the same as `return p;` would be.
                if (!viaInit.isEmpty()
                        && viaInit.stream().allMatch(c -> c.resolved() || c.deferred() != null)) {
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
                    && selectorHoldsCurrentState(decl, va, frame)
                    && isSelectorBinding(decl)
                    && !isReassigned(vref, nameOnlyReassignmentChecks::add)) {
                return List.of(Candidate.of(fromSimpleName, guard, SuccessorForm.SELF));
            }
        }

        // (5) F25 — inside a FOLDED callee, a parameter holds exactly the
        // expression the caller passed at the call site being summarised. Resolve
        // THAT, one frame out, so the fold recovers what entering the callee used
        // to discard. Deliberately last: rules (1)–(4) prove the target from the
        // callee's own text where they can, and only a read they cannot pin needs
        // the caller's argument.
        //
        // The resolution happens in the CALLER's context — one frame out, the
        // caller's from-state, the guard accumulated so far — because that is
        // where the expression is written; the fold now claims exactly what the
        // caller would claim had it inlined the callee, and nothing more. `seen`
        // and MAX_INITIALIZER_DEPTH are the shared cycle guards, so a recursive or
        // mutually recursive forwarder terminates here rather than looping, and
        // terminates UNRESOLVED rather than guessing.
        CtExpression<?> bound = boundArgument(decl, va, frame);
        if (bound != null && depth < MAX_INITIALIZER_DEPTH && seen.add(decl)) {
            String hop = "`" + decl.getSimpleName() + "` := " + BindingFrame.describe(bound)
                    + " at " + frame.describeCallSite();
            List<Candidate> viaArgument =
                    resolve(bound, fromSimpleName, guard, seen, depth + 1, frame.caller());
            if (!viaArgument.isEmpty()) {
                return deferIfCallerCanRead(viaArgument, bound, frame.caller(), guard, hop);
            }
        }
        Candidate gap = Candidate.unresolved(guard, safeText(raw));
        String why = unboundReason(decl, va, frame);
        if (why == null && bound != null) {
            why = "the binding of `" + decl.getSimpleName() + "` was not followed further: "
                    + "the chain exceeded its depth bound or revisited itself";
        }
        return List.of(why == null ? gap : gap.through(why));
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
     *
     * <p>The question is about a VARIABLE, not about a name (finding F13). Java
     * scopes a local to its block, so one method may legally declare the same name
     * in any number of <em>disjoint</em> blocks — and a centralized dispatch is
     * precisely where that happens, because every arm is its own block and every
     * arm wants to call its successor {@code next}. Matching writes by simple name
     * therefore let one arm's accumulator condemn every other arm's local: a
     * single-assignment read was pushed onto the reaching-definitions fallback,
     * which relabels its successor form and — wherever that pass cannot model the
     * control flow enclosing the read, such as a {@code try} or a loop — drops the
     * edge to unresolved outright. {@code examples/scopedlocals} is one rename
     * apart from 5/5 and 4/5.
     *
     * <p>A write is therefore matched on its DECLARATION, <b>by identity</b>. The
     * {@code ==} is load-bearing and {@code equals} is unusable here: Spoon gives
     * {@code CtElement} deep structural equality, so two disjoint arms that both
     * declare {@code Gate next = new Ajar();} compare equal — which is exactly the
     * confusion being removed.
     *
     * @param onNameFallback notified with the variable name when Spoon cannot bind
     *                       a same-named write to any declaration, so identity is
     *                       undecidable and the answer rests on the name alone.
     *                       Reported by the caller rather than let pass for a proof.
     */
    static boolean isReassigned(CtVariableReference<?> vref, Consumer<String> onNameFallback) {
        if (vref == null) return false;
        return isReassigned(vref.getDeclaration(), vref.getSimpleName(), onNameFallback);
    }

    /**
     * The same question asked of a DECLARATION, for a caller that holds one and no
     * read of it — {@code TransitionExtractor.argumentBindings}, deciding whether a
     * callee parameter still holds the argument it was handed at the {@code return}
     * (F25). One implementation, not two: a second notion of "reassigned" would
     * bind a parameter the resolver considers overwritten, and the disagreement
     * would be an edge.
     */
    static boolean isReassigned(CtVariable<?> decl, Consumer<String> onNameFallback) {
        return decl == null ? false : isReassigned(decl, decl.getSimpleName(), onNameFallback);
    }

    private static boolean isReassigned(CtVariable<?> decl, String name,
                                        Consumer<String> onNameFallback) {
        if (decl == null || name == null) return false;
        CtMethod<?> method = decl.getParent(CtMethod.class);
        if (method == null) return false;
        for (CtAssignment<?, ?> a : method.getElements(new TypeFilter<>(CtAssignment.class))) {
            CtExpression<?> lhs = a.getAssigned();
            if (!(lhs instanceof CtVariableAccess<?> vw) || lhs instanceof CtFieldAccess<?>) {
                continue;
            }
            CtVariableReference<?> written = vw.getVariable();
            // The name is a prefilter only — a write to a differently named variable
            // can never be a write to this one — and it keeps the declaration lookup
            // off every unrelated assignment in the method.
            if (written == null || !name.equals(written.getSimpleName())) {
                continue;
            }
            CtVariable<?> writtenDecl = written.getDeclaration();
            if (writtenDecl == null) {
                // The write cannot be bound to a declaration, so identity is
                // undecidable. Answering "reassigned" is the direction that cannot
                // fabricate an edge — it costs a resolvable read, it never invents an
                // unresolvable one — so take it, and record that the answer was a
                // name match rather than a proof.
                if (onNameFallback != null) onNameFallback.accept(name);
                return true;
            }
            if (writtenDecl == decl) {
                return true;
            }
        }
        return false;
    }

    /** {@link #isReassigned(CtVariableReference, Consumer)} with no fallback report. */
    static boolean isReassigned(CtVariableReference<?> vref) {
        return isReassigned(vref, null);
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
