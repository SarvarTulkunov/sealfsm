package io.sealfsm.detect.dispatch;

import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtReturn;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtShadowable;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * The two questions any analysis must answer before it reasons from a callee's
 * body: <em>did we read it?</em> (F11) and <em>can it return normally?</em> (F9).
 *
 * <p>Both were private to {@code TransitionExtractor}, which was correct while
 * the fold and the carrier path were the only things that asked them. The
 * commit-existence probe asks the same two questions at recognition time, and a
 * second copy of either would be a second notion of it — which this codebase
 * refuses everywhere else (one {@code chainOf}, one {@link CommitClassifier}, one
 * {@link CompositionVeto}, one {@code StateNaming}) for the standing reason: the
 * copy drifts, and here the drift would be a body judged unread by one caller and
 * read by another, which is the difference between suppressing an edge and
 * fabricating one. {@code TransitionExtractor} delegates to this class; nothing
 * about its behaviour changes.
 */
public final class CalleeBody {

    private CalleeBody() {
    }

    /**
     * F11 — was this declaration actually <em>read</em> from the source set?
     *
     * <p>For a method outside the source set Spoon supplies a reflective
     * <em>shadow</em>: a real signature with an empty <code>{ }</code> body. That
     * body contains no statements for the same reason it contains no
     * {@code return} — it was never parsed — so nothing may be concluded from what
     * it does not contain. An abstract or interface method with no implementation
     * in the source set answers the same way, and for the same reason.
     *
     * <p>Answering "not read" is always the direction that cannot fabricate: a
     * predicate built on this either declines to suppress an edge (F9) or declines
     * to claim a commit (the probe), and both of those are the conservative side.
     */
    public static boolean wasRead(CtMethod<?> callee) {
        return callee != null && callee.getBody() != null && !isShadow(callee);
    }

    /**
     * F9 — can this method not complete normally?
     *
     * <p>JLS §8.4.7 forbids a non-{@code void} method whose body can complete
     * normally, so a non-void body containing no {@code return} anywhere provably
     * throws or diverges. That makes this <em>exact</em>, not heuristic, which is
     * the only reason a predicate is permitted to suppress an edge at all: it sits
     * on the compiler-checked side of the thesis's epistemic line, beside state
     * enumeration, rather than on the approximate data-flow side.
     *
     * <p>Non-voidness is that licence's precondition and is checked here rather
     * than left to the caller: a {@code void} method completes normally by falling
     * off the end, so an empty one proves the opposite of what this reports. The
     * parameter is a {@link CtMethod} for the same reason — a <em>constructor</em>
     * contains no {@code return} for a purely grammatical reason.
     *
     * <p>The scan is deliberately whole-body and unfiltered, so a {@code return}
     * inside a lambda counts as the method's own. That is the conservative
     * direction: it declines to suppress.
     */
    public static boolean neverReturnsNormally(CtMethod<?> callee) {
        if (callee == null) return false;
        CtBlock<?> body = callee.getBody();
        if (body == null) return false;      // no body visible: never claim anything
        if (isShadow(callee)) return false;  // F11: a stub body is not an empty body
        if (isVoid(callee.getType())) return false;  // no JLS §8.4.7 licence to suppress
        return body.getElements(new TypeFilter<>(CtReturn.class)).isEmpty();
    }

    /** Best-effort {@code void} test; answers "void" when the type is unreadable. */
    public static boolean isVoid(CtTypeReference<?> type) {
        try {
            return type == null || "void".equals(type.getQualifiedName());
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * True when {@code element} was reconstructed by reflection rather than parsed
     * from the source set, so its body is a stub. Checked two ways because either
     * signal alone has failed across Spoon versions: {@code isShadow()} is the
     * declared API, and an invalid source position is the structural consequence
     * (verified — a JDK declaration reports {@code isShadow() == true} and
     * {@code getPosition().isValidPosition() == false}). Best-effort and never
     * throws; when unreadable it answers "shadow", because declining to reason
     * from a body is the direction that cannot drop or invent a transition.
     */
    public static boolean isShadow(CtElement element) {
        try {
            if (element instanceof CtShadowable s && s.isShadow()) return true;
            SourcePosition pos = element.getPosition();
            return pos == null || !pos.isValidPosition();
        } catch (Throwable t) {
            return true;
        }
    }
}
