package io.sealfsm.detect;

import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtTypeReference;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Thin compatibility layer that isolates the Spoon API calls most likely to
 * differ across Spoon versions. Keeping them here means a Spoon upgrade touches
 * exactly one file.
 *
 * <p>The two fragile areas this project depends on are:
 * <ul>
 *   <li><b>Sealed-type access</b> — the {@code CtSealable} interface and
 *       {@code getPermittedTypes()} (added in the Java-17-support line of
 *       Spoon, ~10.x). Handled here with a reflective call plus a structural
 *       fallback that re-derives permitted subtypes from the model.</li>
 *   <li><b>Pattern-matching switches</b> — handled in
 *       {@code TransitionExtractor}, guarded by instanceof + try/catch.</li>
 * </ul>
 */
public final class SpoonCompat {

    private SpoonCompat() {}

    /** True if the type carries the {@code sealed} modifier. */
    public static boolean isSealed(CtType<?> type) {
        try {
            return type.hasModifier(ModifierKind.SEALED);
        } catch (Throwable t) {
            // Extremely old Spoon without SEALED in ModifierKind: fall back to
            // "has explicit permitted types".
            return !rawPermittedTypes(type).isEmpty();
        }
    }

    /**
     * The permitted subtypes of a sealed type, as type references.
     *
     * <p>Prefers the explicit {@code permits} clause via {@code CtSealable}. If
     * that yields nothing (e.g. an implicit permits clause where all subtypes
     * sit in the same file, which some Spoon versions do not populate), falls
     * back to scanning every known subtype reference in the model.
     */
    public static Set<CtTypeReference<?>> permittedTypes(CtType<?> type) {
        Set<CtTypeReference<?>> explicit = rawPermittedTypes(type);
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return derivePermittedFromSubtypes(type);
    }

    @SuppressWarnings("unchecked")
    private static Set<CtTypeReference<?>> rawPermittedTypes(CtType<?> type) {
        // Use reflection so the project still compiles against Spoon builds that
        // expose getPermittedTypes() on slightly different interfaces.
        try {
            var method = type.getClass().getMethod("getPermittedTypes");
            Object result = method.invoke(type);
            if (result instanceof java.util.Collection<?> col) {
                Set<CtTypeReference<?>> out = new LinkedHashSet<>();
                for (Object o : col) {
                    if (o instanceof CtTypeReference<?> ref) out.add(ref);
                }
                return out;
            }
        } catch (ReflectiveOperationException ignored) {
            // no getPermittedTypes() on this build
        }
        return new LinkedHashSet<>();
    }

    /** Fallback: find every type in the model whose direct supertype is {@code type}. */
    private static Set<CtTypeReference<?>> derivePermittedFromSubtypes(CtType<?> type) {
        Set<CtTypeReference<?>> out = new LinkedHashSet<>();
        String rootQn = type.getQualifiedName();
        var factory = type.getFactory();
        for (CtType<?> candidate : factory.getModel().getAllTypes()) {
            if (candidate.getQualifiedName().equals(rootQn)) continue;
            if (directlyExtends(candidate, rootQn)) {
                out.add(candidate.getReference());
            }
        }
        return out;
    }

    private static boolean directlyExtends(CtType<?> candidate, String rootQn) {
        CtTypeReference<?> superClass = candidate.getSuperclass();
        if (superClass != null && rootQn.equals(superClass.getQualifiedName())) {
            return true;
        }
        for (CtTypeReference<?> iface : candidate.getSuperInterfaces()) {
            if (rootQn.equals(iface.getQualifiedName())) return true;
        }
        return false;
    }
}
