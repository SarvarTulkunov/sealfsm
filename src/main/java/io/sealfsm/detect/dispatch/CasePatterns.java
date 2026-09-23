package io.sealfsm.detect.dispatch;

import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtLiteral;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtFieldReference;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.reference.CtVariableReference;

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
        // A constant label is a READ, and a read carries a type: `case DIM ->`
        // reports the enum `Lit`. Answering that here made every arm of a switch
        // over enum constants look like a type pattern for the whole enum, so each
        // was attributed to the composite rather than to the constant it matched.
        // The contract above already says a constant answers null; this enforces it.
        if (caseExpr instanceof CtVariableAccess<?> || caseExpr instanceof CtLiteral<?>) return null;
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

    /** An {@code enum} constant named by a case label: its enum and its name. */
    public record ConstantLabel(String ownerQualifiedName, String constant) {
    }

    /**
     * The {@code enum} constant a label names, or {@code null} when the label is
     * not a constant read ({@code case DIM ->}, {@code case Lit.DIM ->}).
     *
     * <p>The counterpart of {@link #patternType} for the other way a label selects
     * a state: a permitted enum contributes its constants as states, so a constant
     * label selects exactly one of them, as a type pattern selects a permitted
     * class. Whether the owner is a hierarchy member is the caller's question.
     *
     * <p>The declaration is preferred; under noClasspath it may be missing, and a
     * field reference whose owner is an enum is then enough to identify the read.
     */
    public static ConstantLabel enumConstant(Object caseExpr) {
        if (!(caseExpr instanceof CtVariableAccess<?> va)) return null;
        try {
            CtVariableReference<?> vref = va.getVariable();
            if (vref == null) return null;
            if (vref.getDeclaration() instanceof CtEnumValue<?> ev) {
                CtType<?> owner = ev.getDeclaringType();
                return owner == null ? null
                        : new ConstantLabel(owner.getQualifiedName(), ev.getSimpleName());
            }
            if (vref instanceof CtFieldReference<?> fref) {
                CtTypeReference<?> owner = fref.getDeclaringType();
                if (owner != null && owner.getTypeDeclaration() instanceof CtEnum<?>) {
                    return new ConstantLabel(owner.getQualifiedName(), fref.getSimpleName());
                }
            }
        } catch (Throwable ignored) {
            // best effort: an unreadable label is not a constant we can name
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
