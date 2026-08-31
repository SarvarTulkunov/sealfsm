package io.sealfsm.detect.dispatch;

import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtThisAccess;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtVariable;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.List;
import java.util.Set;

/**
 * Is this callee a <em>state mutator</em> — a method that installs whatever
 * hierarchy value it is handed?
 *
 * <pre>{@code
 *   void setState(Hopper next) { this.state = next; }   // yes
 *   void become(Bolt observed) { this.log = observed.toString(); }  // no: commits nothing
 *   void restart(Vent previous) { audit(previous); this.state = new Sealed(); }  // no: see below
 * }</pre>
 *
 * <p>Answering it lets {@code ctx.setState(new Filling())} be read as a commit at
 * any dispatch locus, which is what makes {@link io.sealfsm.model.CommitForm#MUTATOR_ARGUMENT}
 * a position of the commit axis rather than a special case of the F2 fallback.
 * Before the axes were separated, a switch or chain whose arms commit through a
 * mutator was not a recognised dispatch at all: it was rescued only by the
 * whole-hierarchy mutation fallback, and that fallback runs <em>only when nothing
 * else found anything</em>. A hierarchy with one value-returning producer
 * alongside lost every mutator commit it had.
 *
 * <p><b>Nothing here keys on a name (F22).</b> Two hard-coded word lists —
 * {@code setState}/{@code changeState}/{@code transitionTo}/{@code goTo}/
 * {@code setCurrent}/{@code become} — used to admit a method on its spelling
 * alone, and an admitted method has its call sites' ARGUMENT published as the
 * committed successor. An audit hook named {@code become} that commits nothing
 * therefore turned every {@code become(current)} in the model into a resolved
 * self-loop.
 *
 * <p><b>The second clause is the half the obvious structural fix misses.</b>
 * "One H-typed parameter whose body writes an H-typed field" admits
 * {@code restart} above exactly as the word list did — yet its parameter is the
 * state being <em>left</em>, read to be audited, while the successor is chosen by
 * the callee. What licenses reading a call's argument as the target is that the
 * mutator commits <em>what it was handed</em>, and {@link #commitsFrom} is that
 * predicate.
 */
public final class MutatorRecognizer {

    private MutatorRecognizer() {
    }

    /**
     * Does {@code callee} install its single hierarchy-typed parameter into a
     * hierarchy-typed field?
     *
     * <p>The field is identified by its declared TYPE, through the same
     * {@link CommitClassifier#ofTarget} rule every other commit uses, never by its
     * name — two machines in one model routinely both call their field
     * {@code state}, and a name match lets each absorb the other's assignments.
     */
    public static boolean commitsItsArgument(CtMethod<?> callee, Set<String> hierarchy,
                                             String rootQualifiedName) {
        CtParameter<?> param = soleRootParameter(callee, rootQualifiedName);
        if (param == null || callee.getBody() == null) return false;
        try {
            for (CtAssignment<?, ?> a : callee.getElements(new TypeFilter<>(CtAssignment.class))) {
                if (CommitClassifier.ofTarget(a.getAssigned(), hierarchy) == null) continue;
                if (commitsFrom(a.getAssignment(), param, hierarchy)) return true;
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /**
     * The commit a call to a mutator performs, or {@code null} when {@code inv} is
     * not one.
     *
     * <p>Decided on the callee's DECLARATION. An unbindable callee answers
     * {@code null} rather than falling back to the call's shape: this predicate
     * creates a NEW acceptance, and declining costs a commit (which the walk still
     * records as a gap) where guessing would publish an argument as a resolved
     * successor on no evidence at all.
     */
    public static Commit commitOfCall(CtInvocation<?> inv, Set<String> hierarchy,
                                      String rootQualifiedName) {
        try {
            CtExecutableReference<?> exe = inv.getExecutable();
            if (exe == null) return null;
            if (!(exe.getExecutableDeclaration() instanceof CtMethod<?> callee)) return null;
            if (!commitsItsArgument(callee, hierarchy, rootQualifiedName)) return null;
            List<CtExpression<?>> args = inv.getArguments();
            if (args.size() != 1) return null;
            CtExpression<?> arg = args.get(0);
            return CompositionVeto.isHierarchyValue(arg, hierarchy)
                    ? new Commit(io.sealfsm.model.CommitForm.MUTATOR_ARGUMENT, arg, inv) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Exactly one parameter, whose declared type is the hierarchy's ROOT.
     *
     * <p>The root, not merely a member, and the narrowing is load-bearing. A
     * permitted subtype may itself be sealed, and then H(child) is a subset of
     * H(parent) — so a mutator installing the CHILD machine's state
     * ({@code void setState(Body next)}) has a parameter inside the parent's
     * hierarchy too, and reading it as a commit for the parent reports a sum type
     * that merely CONTAINS a machine as being one. {@code examples/nestedroots}
     * caught exactly that: {@code Message} went from correctly abstaining — and
     * re-offering {@code Body} as a root in its own right — to being published as
     * a five-state automaton, with {@code Body} then never classified at all.
     *
     * <p>It costs nothing real. A mutator installs <em>the machine's state</em>,
     * so its parameter is the type the state field is declared with, which is the
     * root; a concrete-state parameter could not accept the other states and would
     * be useless as a commit channel. And it is a TYPE test, not a name test.
     *
     * <p>The same parent/child leak exists in principle for the other commit
     * forms — a {@code Body}-typed field assignment inside {@code Body}'s driver
     * would make {@code Message} a producer today — and no corpus fixture exposes
     * it, because {@code nestedroots.Body} is built on the mutation encoding
     * precisely BECAUSE that was the one encoding no producer recognised. Closing
     * it generally means judging a child against its widest enclosing hierarchy,
     * which is a change to the ownership rule and not to this recognizer.
     */
    private static CtParameter<?> soleRootParameter(CtMethod<?> m, String rootQualifiedName) {
        List<CtParameter<?>> ps = m.getParameters();
        if (ps.size() != 1) return null;
        CtParameter<?> p = ps.get(0);
        CtTypeReference<?> t = p.getType();
        return t != null && t.getQualifiedName().equals(rootQualifiedName) ? p : null;
    }

    /**
     * Is {@code value} committed <em>from</em> {@code param} and from no other
     * hierarchy value? True for {@code next} and for a laundered read such as
     * {@code Objects.requireNonNull(next)}; false for {@code new Sealed()} (the
     * parameter is not read at all) and for
     * {@code next.spent() ? new Spent() : new Live()} (the parameter is read, but
     * what lands in the field is chosen here rather than by the caller).
     *
     * <p>Only hierarchy-typed <em>leaves</em> are counted — constructions, and
     * variable or field reads. An enclosing invocation is a transformation, not a
     * second source of state: were it counted, the laundered form above would be
     * rejected and every call site of a null-checking mutator would lose its edge
     * with no unresolved marker to notice.
     */
    private static boolean commitsFrom(CtExpression<?> value, CtParameter<?> param,
                                       Set<String> hierarchy) {
        if (value == null) return false;
        boolean readsParameter = false;
        for (CtVariableAccess<?> va : value.getElements(new TypeFilter<>(CtVariableAccess.class))) {
            if (isReadOf(va, param)) {
                readsParameter = true;
            } else if (isHierarchyTyped(va, hierarchy)) {
                return false; // a second hierarchy value feeds the commit
            }
        }
        if (!readsParameter) return false;
        for (CtConstructorCall<?> cc : value.getElements(new TypeFilter<>(CtConstructorCall.class))) {
            if (isHierarchyTyped(cc, hierarchy)) return false;
        }
        for (CtThisAccess<?> ta : value.getElements(new TypeFilter<>(CtThisAccess.class))) {
            if (isHierarchyTyped(ta, hierarchy)) return false;
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

    private static boolean isHierarchyTyped(CtExpression<?> e, Set<String> hierarchy) {
        CtTypeReference<?> t = e == null ? null : e.getType();
        return t != null && hierarchy.contains(t.getQualifiedName());
    }
}
