package io.sealfsm.detect.dispatch;

import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.reference.CtTypeReference;

/**
 * Reading a type out of a {@code case} label that is a type pattern.
 *
 * <p>Spoon represents these as {@code CtCasePattern} wrapping a
 * {@code CtTypePattern}, and the accessor names have shifted across versions, so
 * this is fully reflective and best-effort — the same treatment every
 * version-sensitive call in this codebase gets.
 *
 * <p>It lives here, rather than privately inside the extractor where it started,
 * because a dispatch finder and the walker that later visits the same arm must
 * agree about which state the arm matched. Two copies of this would drift into an
 * edge attributed to a state the recognizer never admitted.
 */
public final class CasePatterns {

    private CasePatterns() {
    }

    /**
     * The first type named by a pattern label of {@code c}, whether or not it is a
     * hierarchy member — what was WRITTEN, which is what lets a resolution failure
     * be attributed to the input rather than to the arm handling.
     */
    public static CtTypeReference<?> patternTypeOf(CtCase<?> c) {
        try {
            for (CtExpression<?> ce : c.getCaseExpressions()) {
                CtTypeReference<?> t = patternType(ce);
                if (t != null) return t;
            }
        } catch (Throwable ignored) {
            // best effort: the caller falls back to an unattributed message
        }
        return null;
    }

    /**
     * The type this label's pattern matches, or {@code null} when the label is not
     * a type pattern (a constant, a {@code default}, an unresolvable shape).
     */
    public static CtTypeReference<?> patternType(Object caseExpr) {
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
}
