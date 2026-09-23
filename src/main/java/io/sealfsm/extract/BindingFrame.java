package io.sealfsm.extract;

import spoon.reflect.code.CtAbstractInvocation;
import spoon.reflect.code.CtExpression;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtParameter;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * One activation of the inter-procedural fold: a callee being walked, together
 * with what its caller handed it.
 *
 * <p>F25 kept this as two parallel stacks — the extractor's signature strings and
 * the resolver's parameter maps — pushed and popped together by convention. The
 * convention held, but it made three questions unanswerable: which call site a
 * binding came from (so {@code --explain} could not say), what {@code this} was
 * inside the callee (there was no receiver slot, so every {@code this} read as the
 * current state), and whether a callee was already on the call chain as an OBJECT
 * rather than as a signature string that omits the declaring type. A frame
 * answers all three, and there is exactly one of them.
 *
 * <p><b>{@link #caller} is a link, not a position.</b> A binding's expression is
 * evaluated in the frame it was WRITTEN in, which is normally the frame below —
 * but not always. When a bound argument is itself a call ({@code forward(States
 * .armed())}), folding it opens a new frame whose caller is the frame the
 * argument was written in, while the physical walk is still inside
 * {@code forward}. A stack index would name the wrong frame there; the link names
 * the right one by construction. Recursion and the depth budget are therefore
 * questions about this chain, the call chain being simulated, and not about how
 * deep the Java stack of the analysis happens to be.
 *
 * @param callSite the invocation being summarised
 * @param callee   the body being entered — the unique runtime target of
 *                 {@code callSite}, never merely its static binding
 * @param bindings each callee parameter mapped, by IDENTITY, to the argument
 *                 expression the caller wrote. Parameters that must not be bound
 *                 (reassigned in the body, a varargs slot not handed an explicit
 *                 array) are absent, which is how "unbound" is represented
 * @param receiver the expression {@code this} denotes inside the callee — the
 *                 call's target as the caller wrote it — or {@code null} for a
 *                 static callee, which has no {@code this}. A separate slot
 *                 rather than parameter -1: it is resolved by its own rule
 * @param refusals each parameter deliberately left UNBOUND, with the rule that
 *                 refused it — rendered by {@code --explain} so a reader can tell
 *                 a scope limit from a capability gap. Never consulted by a
 *                 decision: absence from {@code bindings} is the decision
 * @param caller   the frame in which {@code callSite}'s expressions are written,
 *                 or {@code null} when they are written in the dispatch host
 * @param depth    the number of frames on this chain, this one included
 */
public record BindingFrame(CtAbstractInvocation<?> callSite, CtExecutable<?> callee,
                           Map<CtParameter<?>, CtExpression<?>> bindings, CtExpression<?> receiver,
                           Map<CtParameter<?>, String> refusals, BindingFrame caller, int depth) {

    public BindingFrame {
        IdentityHashMap<CtParameter<?>, CtExpression<?>> copy = new IdentityHashMap<>();
        if (bindings != null) copy.putAll(bindings);
        bindings = Collections.unmodifiableMap(copy);
        IdentityHashMap<CtParameter<?>, String> why = new IdentityHashMap<>();
        if (refusals != null) why.putAll(refusals);
        refusals = Collections.unmodifiableMap(why);
    }

    /** A frame opened by {@code callSite}, written in {@code caller}. */
    static BindingFrame open(CtAbstractInvocation<?> callSite, CtExecutable<?> callee,
                             Map<CtParameter<?>, CtExpression<?>> bindings,
                             CtExpression<?> receiver, Map<CtParameter<?>, String> refusals,
                             BindingFrame caller) {
        return new BindingFrame(callSite, callee, bindings, receiver, refusals, caller,
                caller == null ? 1 : caller.depth() + 1);
    }

    /** Why {@code parameter} is unbound here, or {@code null} when it is bound or unknown. */
    String refusalFor(CtParameter<?> parameter) {
        return parameter == null ? null : refusals.get(parameter);
    }

    /**
     * Is {@code executable} already being summarised somewhere on this chain?
     * Decided by IDENTITY of the declaration. The signature string this replaces
     * carries no declaring type, so {@code A.step(E)} delegating to {@code
     * B.step(E)} read as recursion and was declined — a recall loss, and an
     * unexplained one.
     */
    boolean onChain(CtExecutable<?> executable) {
        for (BindingFrame f = this; f != null; f = f.caller()) {
            if (f.callee() == executable) return true;
        }
        return false;
    }

    /** The argument bound to {@code parameter} here, or {@code null} when unbound. */
    CtExpression<?> argumentFor(CtParameter<?> parameter) {
        return parameter == null ? null : bindings.get(parameter);
    }

    /** The call site, as {@code --explain} names it: its text and its source line. */
    String describeCallSite() {
        return BindingFrame.describe(callSite);
    }

    static String describe(CtElement e) {
        if (e == null) return "?";
        String text;
        try {
            text = e.toString().replaceAll("\\s+", " ").trim();
        } catch (Throwable t) {
            text = e.getClass().getSimpleName();
        }
        if (text.isEmpty() && e instanceof spoon.reflect.code.CtThisAccess<?>) {
            text = "this (implicit)";
        }
        if (text.length() > 90) text = text.substring(0, 87) + "...";
        return text + at(e);
    }

    private static String at(CtElement e) {
        try {
            SourcePosition p = e.getPosition();
            if (p == null || !p.isValidPosition() || p.getFile() == null) return "";
            return " [" + p.getFile().getName() + ":" + p.getLine() + "]";
        } catch (Throwable t) {
            return "";
        }
    }
}
