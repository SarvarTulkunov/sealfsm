package io.sealfsm.detect;

import spoon.reflect.CtModel;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What the model could <em>not</em> resolve: every distinct type reference for
 * which {@link SpoonCompat#isUnresolved} answers true, collected in one pass so
 * the analyzer can attribute a thin result to a resolution failure instead of
 * reporting it as a verdict about the program.
 *
 * <p><b>The gap this closes.</b> Models are built with
 * {@code setNoClasspath(true)}, so an unresolvable type still reaches the
 * analysis as a reference with a guessed qualified name. Every recognizer asks
 * {@code hierarchy.contains(ref.getQualifiedName())} against a set built from
 * <em>resolved</em> declarations, so such a reference always answers "not in the
 * hierarchy" — the same answer a genuinely foreign type gives. Nothing
 * downstream could tell the two apart, and the consequences are not cosmetic:
 * withholding one state file from {@code examples/traffic} takes it from 3/3
 * resolved transitions to 1/2 without a single diagnostic mentioning
 * resolution, and a centralized machine whose state files are absent reports
 * "could not determine source state for a switch case" — blaming the switch-arm
 * handling for what is an input problem. In a thesis that reports recall
 * stratified by idiom, that is a misattributed recall figure, not just a
 * confusing message.
 *
 * <p><b>This changes no verdict.</b> An unresolved reference still answers "not
 * in the hierarchy" everywhere, because the alternative — promoting it on a
 * simple-name match — would fabricate a resolved edge to a state that was never
 * established, which is the one failure mode the soundness invariant forbids
 * outright. A simple name is not an identity ({@code examples/namecollision}
 * exists because two states may legally share one), and Spoon's guessed
 * qualified names were observed to be inconsistent for a single type within one
 * build. So this class only ever <em>reports</em>.
 *
 * <p>{@link #resembling} is the one place a simple name is consulted, and it is
 * a <em>reporting</em> heuristic rather than an analysis decision — it selects
 * the text of a diagnostic and never an edge, a state or a classification. That
 * is what keeps it consistent with the project rule that no analysis decision
 * keys on a name (F22).
 */
public final class TypeResolutionAudit {

    /** Distinct unresolved qualified names, sorted so the report is order-free. */
    private final Set<String> names;
    /** simple name → the unresolved qualified names carrying it. */
    private final TreeMap<String, Set<String>> bySimpleName;

    private TypeResolutionAudit(Set<String> names, TreeMap<String, Set<String>> bySimpleName) {
        this.names = names;
        this.bySimpleName = bySimpleName;
    }

    /**
     * Scan the whole model once. Callers build this per model, never per root:
     * the traversal is model-wide, and rebuilding it for each candidate would
     * repeat the most expensive part of the pass for an answer that cannot vary.
     */
    public static TypeResolutionAudit of(CtModel model) {
        Set<String> names = new TreeSet<>();
        TreeMap<String, Set<String>> bySimple = new TreeMap<>();
        for (CtTypeReference<?> ref : model.getElements(new TypeFilter<>(CtTypeReference.class))) {
            if (!SpoonCompat.isUnresolved(ref)) continue;
            String qn = SpoonCompat.resolutionName(ref);
            if (qn == null || qn.isEmpty()) continue;
            names.add(qn);
            bySimple.computeIfAbsent(simpleNameOf(qn), k -> new TreeSet<>()).add(qn);
        }
        return new TypeResolutionAudit(names, bySimple);
    }

    /** An audit with nothing to report — every reference in the model resolved. */
    public static TypeResolutionAudit empty() {
        return new TypeResolutionAudit(new TreeSet<>(), new TreeMap<>());
    }

    public boolean isEmpty() { return names.isEmpty(); }

    public int size() { return names.size(); }

    public Set<String> names() { return java.util.Collections.unmodifiableSet(names); }

    /**
     * The unresolved names that carry one of {@code simpleNames} — the members
     * of some hierarchy, seen through a failed resolution.
     *
     * <p>A match here does not prove the reference <em>is</em> that member, and
     * nothing acts on it as if it did; it is evidence that a thin or empty
     * result for that hierarchy may be a resolution failure rather than a
     * finding, which is exactly the distinction a reader could not previously
     * make.
     */
    public List<String> resembling(Collection<String> simpleNames) {
        Set<String> out = new TreeSet<>();
        for (String simple : simpleNames) {
            Set<String> hit = bySimpleName.get(simple);
            if (hit != null) out.addAll(hit);
        }
        return List.copyOf(out);
    }

    /** {@code n (a.B, c.D, …)} — a count plus at most {@code max} names. */
    public String summary(int max) {
        return summarize(names, max);
    }

    /** The same rendering for any selected subset, so every report reads alike. */
    public static String summarize(Collection<String> selected, int max) {
        List<String> sorted = selected.stream().sorted().toList();
        String shown = sorted.stream().limit(max).collect(Collectors.joining(", "));
        return sorted.size() <= max
                ? "[" + shown + "]"
                : "[" + shown + ", … " + (sorted.size() - max) + " more]";
    }

    private static String simpleNameOf(String qualifiedName) {
        // `$` as well as `.`: Spoon spells a nested type Owner$Nested, and
        // splitting on `.` alone would read that whole tail as the simple name —
        // the canonicalisation StateNaming already learned to apply.
        int cut = Math.max(qualifiedName.lastIndexOf('.'), qualifiedName.lastIndexOf('$'));
        return cut < 0 ? qualifiedName : qualifiedName.substring(cut + 1);
    }

    /** Kept so callers can build a name set without duplicating the split rule. */
    public static Set<String> simpleNames(Collection<String> qualifiedNames) {
        Set<String> out = new LinkedHashSet<>();
        for (String qn : qualifiedNames) out.add(simpleNameOf(qn));
        return out;
    }
}
