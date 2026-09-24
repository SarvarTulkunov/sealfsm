package io.sealfsm.detect.dispatch;

import io.sealfsm.detect.SpoonCompat;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtSuperAccess;
import spoon.reflect.code.CtTypeAccess;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * <em>Which body does this call run?</em> — the question any analysis must answer
 * before it reasons from a callee's body, asked BEFORE the two {@link CalleeBody}
 * answers (was it read, can it return), because both of those are questions
 * about a body and are only meaningful once it is the right one.
 *
 * <p>Spoon hands back the <em>statically bound</em> declaration. That is the body
 * that runs only when the call cannot dispatch anywhere else, and two measured
 * ways it can be something else:
 * <ul>
 *   <li><b>Overriding.</b> {@code d.hook(x)} with {@code d} typed {@code Drv}
 *       binds to {@code Drv#hook} even when the model holds {@code Sub extends
 *       Drv} overriding it. Folding {@code Drv#hook}'s body then publishes its
 *       successors as RESOLVED and never mentions {@code Sub}'s — an overclaim on
 *       one side and a dropped edge with no marker on the other. And F9 read off
 *       the wrong body deletes an edge outright: {@code Drv#reject} may always
 *       throw while {@code Sub#reject} returns a state.</li>
 *   <li><b>Overload guessing under {@code noClasspath}.</b> With {@code over1(Foo,
 *       H)} and {@code over1(Bar, H)} both declared and {@code Bar} unresolvable,
 *       Spoon binds {@code over1(bar, x)} to the {@code Foo} overload (measured,
 *       Spoon 10.4.2 — the first same-arity declaration wins). The binding is a
 *       guess wearing a declaration's identity.</li>
 * </ul>
 *
 * <p>So a call has a unique target only when that is true <em>by construction</em>
 * — a {@code static}, {@code private} or {@code final} method, a {@code super.}
 * call — or when no method in the model overrides the bound declaration in a type
 * related to the receiver's static type. "In the model" is the honest bound: a
 * subclass outside the source set cannot be seen, which is the same limit every
 * other closed-world claim here carries. On that same bound an ABSTRACT bound
 * declaration with exactly one concrete override in a related type has a unique
 * target too — that override (F34): an interface method implemented once in the
 * source set runs that implementation. Two or more is refused as OVERRIDDEN, none
 * as NO_DECLARATION.
 *
 * <p>Nothing here keys on a name (F22): the override test is
 * {@link CtMethod#isOverriding}, a declaration-level fact; the simple name only
 * prefilters the index so that test is not run against every method in the model.
 */
public final class CallTarget {

    /** Why a call's statically bound declaration is not a body the analysis may use. */
    public enum Refusal {
        /** The call bound to no method declaration at all. */
        NO_DECLARATION,
        /** A method in a type related to the receiver's overrides the bound one. */
        OVERRIDDEN,
        /** The receiver's static type did not resolve, so the dispatch set cannot be bounded. */
        UNRESOLVED_RECEIVER,
        /** An argument's type did not resolve and a same-arity overload exists. */
        AMBIGUOUS_OVERLOAD
    }

    /**
     * The verdict: the method whose body runs, or why there is none to name.
     * {@code detail} is rendered for {@code --explain} and never decides anything.
     */
    public record Result(CtMethod<?> method, Refusal refusal, String detail) {
        public boolean unique() {
            return method != null && refusal == null;
        }

        static Result of(CtMethod<?> m) {
            return new Result(m, null, null);
        }

        static Result refused(Refusal r, String detail) {
            return new Result(null, r, detail);
        }
    }

    /**
     * The model's methods indexed by simple name, built once per model. The name
     * is a prefilter only; membership in an override set is decided by
     * {@link CtMethod#isOverriding}.
     */
    public static final class Index {
        private final Map<String, List<CtMethod<?>>> byName = new HashMap<>();
        private final Map<CtMethod<?>, Map<String, Result>> cache = new IdentityHashMap<>();

        public Index(CtModel model) {
            if (model == null) return;
            for (CtMethod<?> m : model.getElements(new TypeFilter<>(CtMethod.class))) {
                byName.computeIfAbsent(m.getSimpleName(), k -> new ArrayList<>()).add(m);
            }
        }

        List<CtMethod<?>> named(String name) {
            return byName.getOrDefault(name, List.of());
        }
    }

    private CallTarget() {
    }

    /** The unique body {@code inv} runs, or the reason there is none. */
    public static Result of(CtInvocation<?> inv, Index index) {
        CtMethod<?> bound = boundDeclaration(inv);
        if (bound == null) {
            return Result.refused(Refusal.NO_DECLARATION, "the call bound to no method declaration");
        }
        Result overload = overloadAmbiguity(inv, bound, index);
        if (overload != null) return overload;
        if (bound.getBody() != null && dispatchIsStatic(inv, bound)) return Result.of(bound);

        CtTypeReference<?> receiver = receiverType(inv, bound);
        if (receiver == null || SpoonCompat.isUnresolved(receiver)) {
            return Result.refused(Refusal.UNRESOLVED_RECEIVER, "the receiver's static type "
                    + (receiver == null ? "is unknown" : receiver.getQualifiedName() + " did not resolve")
                    + ", so the methods the call can dispatch to cannot be bounded");
        }
        String key = receiver.getQualifiedName();
        Map<String, Result> perReceiver = index.cache.computeIfAbsent(bound, k -> new HashMap<>());
        Result cached = perReceiver.get(key);
        if (cached != null) return cached;
        Result r = overriders(bound, receiver, index);
        perReceiver.put(key, r);
        return r;
    }

    /** The declaration Spoon bound the call to, or {@code null}. Never throws. */
    public static CtMethod<?> boundDeclaration(CtInvocation<?> inv) {
        try {
            CtExecutableReference<?> exe = inv.getExecutable();
            if (exe == null) return null;
            return exe.getExecutableDeclaration() instanceof CtMethod<?> m ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Calls whose target is fixed at compile time (JVMS invokestatic /
     * invokespecial, or a method nothing can override): the bound declaration IS
     * the body that runs, with no set to bound.
     */
    private static boolean dispatchIsStatic(CtInvocation<?> inv, CtMethod<?> m) {
        try {
            if (m.isStatic() || m.isPrivate() || m.isFinal()) return true;
            return inv.getTarget() instanceof CtSuperAccess<?>;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The static type of the object the call dispatches on: the target the caller
     * wrote, or — for an unqualified call — the enclosing class, whose own subtypes
     * may override the method exactly as an explicit receiver's may.
     */
    private static CtTypeReference<?> receiverType(CtInvocation<?> inv, CtMethod<?> bound) {
        try {
            CtExpression<?> target = inv.getTarget();
            if (target != null && !(target instanceof CtTypeAccess<?>)) {
                CtTypeReference<?> t = target.getType();
                if (t != null) return t;
            }
            CtType<?> enclosing = inv.getParent(CtType.class);
            if (enclosing != null) return enclosing.getReference();
            CtType<?> declaring = bound.getDeclaringType();
            return declaring == null ? null : declaring.getReference();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Does any other method in the model override {@code bound} in a type related
     * to the receiver's static type? A subtype of it is one the receiver may BE at
     * run time; a supertype of it lying below the bound declaration means Spoon
     * bound past an override the receiver's type already sees. Either way the bound
     * body is not the one that runs. An abstract re-declaration has no body to run
     * and is skipped; the concrete overrides beneath it are found in their own
     * right.
     */
    private static Result overriders(CtMethod<?> bound, CtTypeReference<?> receiver, Index index) {
        CtType<?> receiverDecl;
        try {
            receiverDecl = receiver.getTypeDeclaration();
        } catch (Throwable t) {
            receiverDecl = null;
        }
        if (receiverDecl == null) {
            return Result.refused(Refusal.UNRESOLVED_RECEIVER, "the receiver's static type "
                    + receiver.getQualifiedName() + " has no declaration in the model");
        }
        int arity = bound.getParameters().size();
        List<CtMethod<?>> bodies = new ArrayList<>();
        if (bound.getBody() != null) bodies.add(bound);
        for (CtMethod<?> m : index.named(bound.getSimpleName())) {
            if (m == bound || m.getBody() == null || m.getParameters().size() != arity) continue;
            CtType<?> declaring = m.getDeclaringType();
            if (declaring == null) continue;
            if (!overrides(m, bound)) continue;
            if (related(declaring, receiver, receiverDecl)) bodies.add(m);
        }
        // An ABSTRACT bound declaration runs no body of its own, so the set of
        // bodies the call can run is exactly its concrete overrides. One of them
        // is the unique target on the same closed-world bound the bodied case
        // rests on: `this.handle(state)` in an interface's default method, whose
        // only implementation is in the model, runs that implementation and
        // nothing else. Zero overrides is not a target at all.
        if (bodies.size() == 1) return Result.of(bodies.get(0));
        if (bodies.isEmpty()) {
            return Result.refused(Refusal.NO_DECLARATION, describe(bound)
                    + " is abstract and no implementation related to the receiver is in the model");
        }
        CtMethod<?> other = bodies.get(0) == bound ? bodies.get(1) : bodies.get(0);
        return Result.refused(Refusal.OVERRIDDEN, "the call is virtual and "
                + describe(bound) + (bound.getBody() == null
                        ? " is abstract with more than one implementation, e.g. " + describe(other)
                        : " is overridden by " + describe(other))
                + ", so more than one body can run");
    }

    private static boolean overrides(CtMethod<?> m, CtMethod<?> bound) {
        try {
            return m.isOverriding(bound);
        } catch (Throwable t) {
            // Unanswerable under noClasspath: fall back to the signature, which
            // over-reports an override — the direction that refuses a body rather
            // than trusting a wrong one.
            return m.getSignature().equals(bound.getSignature());
        }
    }

    private static boolean related(CtType<?> declaring, CtTypeReference<?> receiver,
                                   CtType<?> receiverDecl) {
        try {
            return declaring == receiverDecl
                    || declaring.isSubtypeOf(receiver)
                    || receiverDecl.isSubtypeOf(declaring.getReference());
        } catch (Throwable t) {
            return true; // cannot tell: treat as reachable, the direction that refuses
        }
    }

    /**
     * Under {@code noClasspath} an argument whose type did not resolve gives JDT
     * nothing to choose an overload with, and Spoon then binds the FIRST
     * same-arity declaration. When the declaring type has another method of that
     * name the binding is therefore a guess, not a resolution.
     */
    private static Result overloadAmbiguity(CtInvocation<?> inv, CtMethod<?> bound, Index index) {
        List<CtExpression<?>> args;
        try {
            args = inv.getArguments();
        } catch (Throwable t) {
            return null;
        }
        CtExpression<?> unreadable = null;
        for (CtExpression<?> a : args) {
            CtTypeReference<?> t;
            try {
                t = a.getType();
            } catch (Throwable e) {
                t = null;
            }
            if (t == null || SpoonCompat.isUnresolved(t)) {
                unreadable = a;
                break;
            }
        }
        if (unreadable == null) return null;
        CtType<?> declaring = bound.getDeclaringType();
        int n = args.size();
        for (CtMethod<?> m : index.named(bound.getSimpleName())) {
            if (m == bound || m.getDeclaringType() == null) continue;
            if (!sameOrRelatedType(m.getDeclaringType(), declaring)) continue;
            if (arityAccepts(m, n)) {
                return Result.refused(Refusal.AMBIGUOUS_OVERLOAD, "argument `"
                        + text(unreadable) + "` has an unresolved type and " + describe(m)
                        + " is another overload the call could denote; Spoon's choice of "
                        + describe(bound) + " is a guess");
            }
        }
        return null;
    }

    private static boolean sameOrRelatedType(CtType<?> a, CtType<?> b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        try {
            return a.isSubtypeOf(b.getReference()) || b.isSubtypeOf(a.getReference());
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean arityAccepts(CtMethod<?> m, int n) {
        List<CtParameter<?>> ps = m.getParameters();
        if (ps.size() == n) return true;
        if (ps.isEmpty()) return false;
        try {
            return ps.get(ps.size() - 1).isVarArgs() && n >= ps.size() - 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String describe(CtMethod<?> m) {
        try {
            CtType<?> d = m.getDeclaringType();
            return (d == null ? "?" : d.getSimpleName()) + "#" + m.getSignature();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String text(CtExpression<?> e) {
        try {
            return e.toString().replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            return "?";
        }
    }
}
