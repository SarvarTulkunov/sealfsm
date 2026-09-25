package io.sealfsm.detect.dispatch;

import io.sealfsm.detect.DispatchCommitDetector;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Set;

/**
 * F34's run-to-completion driver, as one predicate: a host that discriminates the
 * state handed to it and re-enters ITSELF with a hierarchy value in that
 * parameter's position.
 *
 * <pre>{@code
 *   default OrderState orchestrate(OrderState s) {
 *       return switch (s) {
 *           case Placed p -> orchestrate(handle(p));   // re-entry: handle(p) is the successor
 *           case Fulfilled f -> f;                      // halting arm
 *           ...
 *       };
 *   }
 * }</pre>
 *
 * <p>This is the tail-recursive spelling of {@code while (!done) s = step(s);}. Two
 * pieces of code need the answer, and they need the same one. The extractor reads
 * the re-entry argument as the successor, never the driver's final result. The
 * installation analysis ({@link Installation}, F36) reads the re-entry as the
 * place the successor becomes the current state. If the two disagreed about which
 * methods are drivers, a driver would either be walked one way and installed the
 * other, or not installed at all.
 */
public final class RunToCompletion {

    private RunToCompletion() {
    }

    /**
     * The parameter position through which {@code method} re-enters itself, or
     * {@code -1}.
     *
     * <p>The selector is the root-typed parameter, or failing that the first
     * hierarchy-typed one, the same rule the extractor uses to choose a
     * centralized method's selector. The method must discriminate the state
     * ({@link DispatchCommitDetector#discriminatesState}). Some call in its own
     * body must also have this very method as its unique runtime target
     * ({@link CallTarget}) and pass a hierarchy value in the selector's position.
     * A call inside a lambda or a local class runs on another schedule and does
     * not count. The decision is taken on the declaration's identity, never on a
     * name.
     */
    public static int reentryIndex(CtMethod<?> method, Set<String> hierarchy, String rootQualifiedName,
                                   CallTarget.Index index) {
        if (method == null || method.getBody() == null) return -1;
        int selector = selectorIndex(method.getParameters(), hierarchy, rootQualifiedName);
        if (selector < 0) return -1;
        if (!DispatchCommitDetector.discriminatesState(method, hierarchy, rootQualifiedName)) return -1;
        try {
            for (CtInvocation<?> inv : method.getBody().getElements(new TypeFilter<>(CtInvocation.class))) {
                if (inv.getParent(CtExecutable.class) != method) continue;
                if (runs(inv, method, index) && inv.getArguments().size() > selector
                        && isHierarchyTyped(inv.getArguments().get(selector), hierarchy)) {
                    return selector;
                }
            }
        } catch (Throwable t) {
            return -1;
        }
        return -1;
    }

    /** Does {@code method} re-enter itself through parameter {@code position}? */
    public static boolean reentersThrough(CtMethod<?> method, int position, Set<String> hierarchy,
                                          String rootQualifiedName, CallTarget.Index index) {
        return position >= 0 && reentryIndex(method, hierarchy, rootQualifiedName, index) == position;
    }

    /** Does {@code inv} run {@code host}, provably and uniquely? */
    public static boolean runs(CtInvocation<?> inv, CtMethod<?> host, CallTarget.Index index) {
        if (CallTarget.boundDeclaration(inv) != host) return false;
        CallTarget.Result target = CallTarget.of(inv, index);
        return target.unique() && target.method() == host;
    }

    /**
     * The selector position: the parameter declared with the root, or the first
     * declared with any member. Positional and identity-based, since Spoon's
     * structural {@code equals} would let two same-typed parameters stand in for
     * one another.
     */
    private static int selectorIndex(List<CtParameter<?>> params, Set<String> hierarchy,
                                     String rootQualifiedName) {
        for (int i = 0; i < params.size(); i++) {
            CtTypeReference<?> t = params.get(i).getType();
            if (t != null && rootQualifiedName.equals(t.getQualifiedName())) return i;
        }
        for (int i = 0; i < params.size(); i++) {
            CtTypeReference<?> t = params.get(i).getType();
            if (t != null && hierarchy.contains(t.getQualifiedName())) return i;
        }
        return -1;
    }

    private static boolean isHierarchyTyped(CtExpression<?> e, Set<String> hierarchy) {
        CtTypeReference<?> t = e == null ? null : e.getType();
        return t != null && hierarchy.contains(t.getQualifiedName());
    }
}
