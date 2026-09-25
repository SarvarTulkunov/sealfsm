package io.sealfsm.detect.dispatch;

import io.sealfsm.model.CommitForm;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtIf;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtLoop;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtExecutableReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The <b>k = 1 commit-existence probe</b>: for a dispatch whose branches are bare
 * calls, open each callee's body <em>once</em> and ask only whether it installs a
 * hierarchy value.
 *
 * <p>It answers a strictly weaker question than {@code TransitionResolver} does.
 * The resolver asks <em>which state</em> the successor is; this asks only
 * <em>whether there is a commit at all</em>, and deliberately never attempts the
 * first. The two are independent limits and are reported independently: a
 * hierarchy admitted here is a Tier 2 machine — complete states, a proven commit,
 * and one explicitly unresolved edge per dispatched arm — not a machine with a
 * recovered relation.
 *
 * <h2>Why the probe exists</h2>
 * {@link CommitClassifier} reads the commit from the dispatch's own syntactic
 * context, which is right and is the precision guard. But a dispatch spelled as a
 * {@code switch} <em>statement</em> whose arms are calls has no such context:
 *
 * <pre>{@code
 *   switch (state) {                          // no parent return, no assignment,
 *       case Idle i  -> advance(i, signal);   // no local declaration — nothing
 *       case Armed a -> advance(a, signal);   // here can prove or disprove a
 *       case Fired f -> advance(f, signal);   // commit
 *   }
 *   private void advance(Latch cur, Signal s) { this.state = table.get(...); }
 * }</pre>
 *
 * <p>That is a genuine state machine, and before this probe the whole hierarchy
 * was lost — not merely its transitions, but its <em>states</em>, because the
 * analyzer only enumerated states on the accepted path.
 *
 * <h2>The one clause, and why the other three collapse into it</h2>
 * The probe applies to a call in <b>statement position</b>, whose value Java
 * discards (JLS §14.8). That single fact settles what may count as evidence:
 *
 * <ul>
 *   <li><b>An H-typed field write inside the callee is a commit.</b> It is a side
 *       effect, so it survives the caller discarding the call's value. This is the
 *       whole rule.</li>
 *   <li><b>A {@code return} inside the callee is not.</b> Whatever the callee's
 *       codomain, <em>this</em> caller throws the value away, so nothing is
 *       installed. Counting it would admit {@code describe(state);} called for its
 *       (ignored) result.</li>
 *   <li><b>An H-typed <em>local</em> inside the callee is not.</b> A local dies
 *       with the frame. The prompt's "an H-typed local that is subsequently
 *       committed" needs no rule of its own: if it is subsequently committed, the
 *       committing act is itself a field write in the same body, which the clause
 *       above already sees. A second rule for it would be a second notion of one
 *       thing.</li>
 *   <li><b>A mutator call inside the callee is not asked about</b>, and this is a
 *       deliberate scope line rather than an oversight. {@link MutatorRecognizer}
 *       decides by reading the <em>mutator's</em> body, which from here is a second
 *       hop; the probe's depth is exactly 1 and is independent of the k = 2
 *       resolution budget. A dispatch whose arms call a helper that calls a setter
 *       is therefore a Tier 3 candidate, reported with its complete state set. The
 *       one-hop spelling — an arm calling the mutator directly — is already
 *       recognised by {@code DispatchCommitDetector}'s own mutator test, which runs
 *       before this probe.</li>
 * </ul>
 *
 * <h2>What keeps it from becoming "any exhaustive switch is a machine"</h2>
 * The type of what is installed, and nothing else. {@code examples/voidfold} is
 * indistinguishable from {@code examples/voidcommit} at the call site — same
 * hierarchy, same exhaustive dispatch, same method name and arity, the matched
 * state passed as the argument — and differs only in that its callee writes a
 * {@code String} field. That is {@code examples/foreignfold} one indirection
 * deeper, and it must stay rejected: every sealed sum type in Java is eventually
 * switched over, so a probe that asked merely "does the callee do something"
 * would turn {@code String describe(Shape)} into a two-state automaton.
 *
 * <p>The existing rules are untouched. This runs strictly <em>after</em>
 * {@link CommitClassifier} and {@link MutatorRecognizer} have both declined, so it
 * can only add machines and can never re-attribute one — and
 * {@link CompositionVeto} still runs after it, so a tree rewrite does not become a
 * machine by hiding its recursion behind a helper.
 *
 * <p><b>Nothing here keys on a name (F22)</b> — not the callee's, not the field's,
 * not the parameter's. Membership is decided by {@link CommitClassifier#ofTarget},
 * the same declared-type rule every other commit uses.
 */
public final class CommitProbe {

    private CommitProbe() {
    }

    /**
     * What one probe of a dispatch's branches found.
     *
     * @param commit     the commit the probe established, or {@code null} when none
     *                   was — which includes both "the callee installs nothing in
     *                   H" and "the callee could not be read"
     * @param unreadable rendered descriptions of the callees the analysis could not
     *                   read <em>and that were not shadows</em> (F11) — an abstract
     *                   or interface method with no implementation in the source
     *                   set, or a call that bound to no declaration at all. Carried
     *                   rather than folded into the verdict because the two are
     *                   different reports: "I read it and it commits nothing" is a
     *                   finding about the program, "I could not read it" is a
     *                   finding about the invocation, and a user can act on the
     *                   second. The verdict itself does not distinguish them —
     *                   neither is evidence of a commit
     * @param probed     the callees whose bodies WERE read and scanned. When no
     *                   commit was found this is what "no commit within one call"
     *                   rests on (F32): a candidate may only say it looked one call
     *                   deep if it did
     */
    public record Result(Commit commit, List<String> unreadable, List<String> probed) {
        public Result {
            unreadable = List.copyOf(unreadable);
            probed = List.copyOf(probed);
        }
    }

    /** The commit established by probing these branches, or {@code null}. */
    public static Commit probe(List<? extends CtElement> branches, Set<String> hierarchy,
                               String rootQualifiedName) {
        return of(branches, hierarchy, rootQualifiedName).commit();
    }

    /**
     * Probe every statement-position call in {@code branches}, one callee body
     * deep.
     *
     * <p>One commit anywhere in the dispatch is enough to establish that the
     * dispatch commits, exactly as {@code mutatorCommitIn} and
     * {@code commitFormOfChain} already treat their branches: the question is
     * whether this discrimination installs successors, not whether every arm does.
     * Which arm resolves to which state is the resolver's question, and this probe
     * never asks it.
     */
    public static Result of(List<? extends CtElement> branches, Set<String> hierarchy,
                            String rootQualifiedName) {
        Commit found = null;
        Set<String> unreadable = new LinkedHashSet<>();
        Set<String> probed = new LinkedHashSet<>();
        for (CtElement branch : branches) {
            if (branch == null) continue;
            for (CtInvocation<?> inv : statementCalls(branch)) {
                CtMethod<?> callee = calleeOf(inv);
                if (callee == null || !CalleeBody.wasRead(callee)) {
                    // F11 — a body the analysis did not read may not be reasoned
                    // from. Its emptiness is not evidence of anything, so the arm
                    // leaves the hierarchy a candidate rather than a machine.
                    //
                    // The VERDICT is the same whichever kind of unreadable this is;
                    // the REPORT is stratified, for the reason TypeResolutionAudit
                    // already stratifies its severities — one that does not
                    // discriminate is one a reader learns to ignore. A shadow is
                    // the ordinary out-of-model case (every `Objects.equals` and
                    // every synthesised record accessor is one, and Spoon leaks an
                    // arm's `when` clause into its body as a statement, so guard
                    // calls land here too); a declaration that WAS read and simply
                    // has no body — an abstract or interface method with no
                    // implementation in the source set — is the case a user can
                    // act on, and is what examples/unreadablecallee pins.
                    if (callee == null || !CalleeBody.isShadow(callee)) {
                        unreadable.add(describe(inv, callee));
                    }
                    continue;
                }
                // F9 — a non-void callee with no `return` anywhere provably cannot
                // complete normally (JLS §8.4.7). It is an undefined input: it
                // contributes no evidence of a commit and no edge. Asked BEFORE the
                // body is scanned, or such a callee's incidental bookkeeping write
                // would be read as a commit.
                if (CalleeBody.neverReturnsNormally(callee)) continue;
                probed.add(describe(inv, callee));
                if (found != null) continue;
                CtAssignment<?, ?> write = rootFieldWrite(callee, hierarchy, rootQualifiedName);
                if (write != null) {
                    found = new Commit(CommitForm.FIELD_MUTATION, null, inv);
                }
            }
        }
        return new Result(found, new ArrayList<>(unreadable), new ArrayList<>(probed));
    }

    /**
     * Does this callee's body assign to a field declared with the hierarchy's
     * <em>root</em> type?
     *
     * <p>The declared TYPE of the target decides, through the same
     * {@link CommitClassifier#ofTarget} rule every other commit form uses — never
     * the field's name, and never the callee's. {@code LOCAL_ACCUMULATOR} is
     * excluded on purpose: a local dies with the callee's frame and installs
     * nothing the caller can observe.
     *
     * <p><b>The root, not merely a member, and the narrowing is load-bearing</b> —
     * it is the same one {@link MutatorRecognizer#soleRootParameter} makes, found
     * here by the same fixture. A permitted subtype may itself be sealed, so
     * H(child) is a subset of H(parent), and a field typed with the CHILD's root is
     * inside the parent's hierarchy too. Without this clause
     * {@code examples/nestedroots} regressed exactly as it did for the mutator:
     * {@code BodyContext.setState} writes a {@code Body}-typed field, so probing
     * it while classifying {@code Message} proved a commit for {@code Message} —
     * publishing the containing sum type as a five-state automaton and, because an
     * accepted parent claims its members, losing {@code Body} (the real machine)
     * altogether.
     *
     * <p>It costs nothing real. A state field must be able to hold every state, so
     * it is declared with the root; a field typed with one concrete state could not
     * be the machine's state. And it is a TYPE test, not a name test.
     *
     * <p>The scan is the callee's own body and stops there. It does not follow a
     * further call, which is what makes the depth exactly 1.
     */
    private static CtAssignment<?, ?> rootFieldWrite(CtMethod<?> callee, Set<String> hierarchy,
                                                     String rootQualifiedName) {
        try {
            for (CtAssignment<?, ?> a
                    : callee.getBody().getElements(new TypeFilter<>(CtAssignment.class))) {
                if (isRootFieldWrite(a, hierarchy, rootQualifiedName)) return a;
            }
        } catch (Throwable ignored) {
            // an unreadable body answers "no commit", the direction that cannot
            // fabricate a machine
        }
        return null;
    }

    /**
     * The probe's one commit clause, for a single assignment: a write to a field
     * declared with the hierarchy ROOT. Public so the extractor's fold into the
     * same callee (F29) reads a commit by the same rule the probe proved it by —
     * two notions of "this write installs a state" would let the fold resolve a
     * write the probe never counted, or skip the one it did.
     */
    public static boolean isRootFieldWrite(CtAssignment<?, ?> a, Set<String> hierarchy,
                                           String rootQualifiedName) {
        try {
            if (CommitClassifier.ofTarget(a.getAssigned(), hierarchy) != CommitForm.FIELD_MUTATION) {
                return false;
            }
            CtTypeReference<?> written = a.getAssigned().getType();
            return written != null && written.getQualifiedName().equals(rootQualifiedName);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Every invocation inside {@code branch} that sits in <em>statement</em>
     * position — the only place the probe applies, because that is the only place
     * where the call's own value cannot be the commit.
     *
     * <p>The synthetic {@code CtYieldStatement} Spoon wraps an arrow arm of a
     * switch <em>statement</em> in is unwrapped here (the same F10 rule the walker
     * applies): {@code yield} is illegal outside a switch expression, so a yield
     * directly under a {@code CtSwitch} stands for a discarded expression
     * statement. A yield under a {@code CtSwitchExpression} is genuine and its
     * value IS consumed, so it is not statement position and is excluded.
     */
    private static List<CtInvocation<?>> statementCalls(CtElement branch) {
        List<CtInvocation<?>> out = new ArrayList<>();
        try {
            for (CtInvocation<?> inv : branch.getElements(new TypeFilter<>(CtInvocation.class))) {
                if (isStatementPosition(inv)) out.add(inv);
            }
        } catch (Throwable ignored) {
            // best effort: an unreadable branch contributes no evidence
        }
        return out;
    }

    static boolean isStatementPosition(CtExpression<?> inv) {
        try {
            CtElement parent = inv.isParentInitialized() ? inv.getParent() : null;
            if (parent == null) return false;
            if (parent instanceof CtBlock<?>) return true;
            // A colon arm holds a statement list, so a call directly under a case
            // is a statement. An ARROW arm is never spelled this way: Spoon wraps
            // it in a yield or a block, handled below.
            if (parent instanceof CtCase<?>) return true;
            if (parent instanceof CtYieldStatement ys) {
                CtElement gp = ys.isParentInitialized() ? ys.getParent() : null;
                // F10: synthetic yield of a switch STATEMENT arm — a discarded value.
                return gp instanceof CtCase<?> arm && arm.isParentInitialized()
                        && arm.getParent() instanceof CtSwitch<?>;
            }
            // A braceless branch body: `if (c) advance(i, e);`
            if (parent instanceof CtIf ctIf) {
                return ctIf.getThenStatement() == inv || ctIf.getElseStatement() == inv;
            }
            if (parent instanceof CtLoop loop) return loop.getBody() == inv;
            return false;
        } catch (Throwable t) {
            return false;  // unknown position: assume the value is consumed
        }
    }

    private static CtMethod<?> calleeOf(CtInvocation<?> inv) {
        try {
            CtExecutableReference<?> exe = inv.getExecutable();
            if (exe == null) return null;
            return exe.getExecutableDeclaration() instanceof CtMethod<?> m ? m : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * How an unreadable callee is named in a diagnostic. Best effort and purely
     * descriptive: it selects the text of a message, never a decision, which is
     * what keeps it clear of the rule that no analysis decision keys on a name.
     */
    private static String describe(CtInvocation<?> inv, CtMethod<?> callee) {
        try {
            if (callee != null) {
                CtType<?> declaring = callee.getDeclaringType();
                return (declaring == null ? "?" : declaring.getQualifiedName())
                        + "#" + callee.getSignature();
            }
            CtExecutableReference<?> exe = inv.getExecutable();
            return exe == null ? "?" : exe.getSignature();
        } catch (Throwable t) {
            return "?";
        }
    }
}
