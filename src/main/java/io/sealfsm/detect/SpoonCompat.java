package io.sealfsm.detect;

import spoon.reflect.declaration.CtType;
import spoon.reflect.declaration.ModifierKind;
import spoon.reflect.reference.CtArrayTypeReference;
import spoon.reflect.reference.CtTypeParameterReference;
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
 *   <li><b>Type resolution under {@code noClasspath}</b> — see
 *       {@link #isUnresolved}. Every model this tool builds sets
 *       {@code setNoClasspath(true)}, under which a reference Spoon cannot bind
 *       is still produced, carrying a <em>guessed</em> qualified name. Whether a
 *       reference actually resolved is therefore a precondition of every
 *       membership test in the analysis, and the signal for it is
 *       version-sensitive, so it lives here.</li>
 * </ul>
 */
public final class SpoonCompat {

    private SpoonCompat() {}

    /** Spoon's spelling of the type of the {@code null} literal. */
    private static final String NULL_TYPE = "<nulltype>";

    /**
     * True when Spoon could <em>not</em> bind {@code ref} to a type declaration.
     *
     * <p><b>Why this predicate has to exist.</b> Every model is built with
     * {@code setNoClasspath(true)}, so a reference to a type that is neither in
     * the source set nor on the classpath does not fail — Spoon fabricates a
     * reference and <em>guesses</em> its qualified name, usually by prepending
     * the compilation unit's own package. Every recognizer in this tool decides
     * hierarchy membership with
     * {@code hierarchy.contains(ref.getQualifiedName())} over a set built from
     * <em>resolved</em> declarations, so an unresolved reference always answers
     * "not in the hierarchy" — indistinguishable from a genuine negative. That
     * is a silent under-report, and this predicate is what makes it observable.
     *
     * <p>The guess is not even stable: one unresolvable {@code Signal} was
     * observed reaching the model as both {@code pkg.Signal} and a bare
     * {@code Signal} within a single build. So a name can neither confirm nor
     * refute membership here, and this answers only the question it can answer —
     * <em>did it resolve?</em> — never "is it really a state".
     *
     * <p>Keyed on {@code getTypeDeclaration() == null}, which was verified to be
     * exactly the discriminator wanted: a type from the source set and a type
     * reconstructed from the classpath both answer non-null (the latter a shadow
     * declaration, whose qualified name is still authoritative — see F11), while
     * only a genuinely unbound reference answers null. The remaining cases carry
     * no declaration for reasons that are not failures and are excluded
     * explicitly: primitives and {@code void}, the null literal's type, type
     * parameters and wildcards. An array is resolved exactly when its component
     * type is.
     *
     * <p><b>An IMPLICIT reference is excluded, and that exclusion is what makes
     * the predicate usable.</b> Spoon synthesises the members a {@code record}
     * declares implicitly, and the accessor body it generates — {@code return
     * this.counter;} — carries an implicit {@code this} access whose type
     * reference is built from the bare simple name with no package, so it never
     * binds. Every record state and every record event in the corpus produces
     * one: without this, four otherwise clean fixtures reported resolution
     * failures for {@code Timeout}, {@code Pending}, {@code Error} and their
     * kind, and three correctly-rejected event alphabets were told their
     * rejection might be a resolution failure. A diagnostic that cries wolf
     * trains a reader to ignore the channel, which costs more than it can pay
     * back. The exclusion is also right on the merits rather than merely
     * convenient: an implicit reference was never written in the source, so it
     * is not evidence that the source mentions a type the analysis could not
     * read. A genuinely missing type is always referred to explicitly somewhere
     * too, and that occurrence is scanned on its own.
     *
     * <p>{@code isImplicit()} is checked, NOT source position, though both look
     * plausible and the codebase uses position elsewhere (F11's shadow test).
     * Position was measured and does not separate these: a type pattern's
     * reference ({@code case Shut s}) reports no valid position while being a
     * perfectly real, explicitly written failure, so filtering on position would
     * discard the true positives along with the noise. Only the parent's
     * implicitness would over-reject in the same way — {@code isImplicit()} on
     * the reference itself is the signal, and it is exact on the corpus.
     *
     * <p>Never throws. When the question itself is unanswerable the answer is
     * "unresolved", because the only consequence is a diagnostic — unlike F9's
     * suppression rule, nothing here can drop or fabricate a transition, so the
     * safe direction is to report rather than to stay quiet.
     */
    public static boolean isUnresolved(CtTypeReference<?> ref) {
        if (ref == null) return false;
        try {
            if (ref.isImplicit()) return false;
            CtTypeReference<?> t = componentTypeOf(ref);
            if (t.isImplicit()) return false;
            if (t.isPrimitive() || t instanceof CtTypeParameterReference) return false;
            String qn = t.getQualifiedName();
            if (qn == null || qn.isEmpty() || NULL_TYPE.equals(qn) || "void".equals(qn)) {
                return false;
            }
            return t.getTypeDeclaration() == null;
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * The qualified name to report {@code ref} under: an array is named by its
     * component type, since that is the type that failed to resolve.
     */
    public static String resolutionName(CtTypeReference<?> ref) {
        if (ref == null) return "<null>";
        try {
            return componentTypeOf(ref).getQualifiedName();
        } catch (Throwable e) {
            return "<unreadable>";
        }
    }

    /** Peel array nesting down to the element type. */
    private static CtTypeReference<?> componentTypeOf(CtTypeReference<?> ref) {
        CtTypeReference<?> t = ref;
        while (t instanceof CtArrayTypeReference<?> arr) {
            CtTypeReference<?> component = arr.getComponentType();
            if (component == null) break;
            t = component;
        }
        return t;
    }

    /**
     * The members of {@code type}'s {@code permits} clause that did not resolve.
     *
     * <p>This is the severe case, because it lands on the one claim the thesis
     * makes exactly. {@code StateExtractor} reads states from the permits
     * <em>references</em>, so such a state is still enumerated by name — but its
     * declaration was never read, so if it is itself sealed or an {@code enum}
     * its child states are silently absent, and because
     * {@link StateMachineClassifier#hierarchyQualifiedNames} is built from
     * resolved declarations only, it is missing from every membership set and no
     * transition touching it can be recognised.
     *
     * <p>Empty whenever the permits clause was recovered structurally rather than
     * read (see {@link #permittedTypes}), since that fallback can only ever yield
     * types the model already holds.
     */
    public static Set<CtTypeReference<?>> unresolvedPermittedTypes(CtType<?> type) {
        Set<CtTypeReference<?>> out = new LinkedHashSet<>();
        for (CtTypeReference<?> ref : permittedTypes(type)) {
            if (isUnresolved(ref)) out.add(ref);
        }
        return out;
    }

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
