package io.sealfsm.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single, injective mapping from a state's fully-qualified name to the
 * {@link State#id() id} that names it everywhere downstream — DOT node ids,
 * SCXML {@code id}/{@code target} attributes, and both endpoints of every
 * {@link Transition}.
 *
 * <p><b>Why this exists.</b> A state's id used to be its bare simple name, which
 * is not unique: a {@code permits} clause may legally name two subtypes that
 * share one. Three shapes reach it, and none is exotic —
 * <ul>
 *   <li>{@code permits Idle, Holder.Idle} — a nested type beside a top-level one,
 *       legal even in the unnamed module because both live in the same package;</li>
 *   <li>{@code permits a.Foo, b.Foo} — legal in a named module, where the
 *       same-package restriction does not apply;</li>
 *   <li>two permitted {@code enum}s sharing a constant name ({@code Phase.IDLE}
 *       and {@code Mode.IDLE}), by far the likeliest of the three, since an
 *       enum's constants become child states under their bare names.</li>
 * </ul>
 * Colliding ids do not merely draw badly. Two distinct edges between two
 * distinct states become the same {@code (from, to, event, guard)} tuple, and
 * the extractor's transition set discards one — a transition dropped with no
 * unresolved marker, which is precisely the outcome the record-everything
 * invariant exists to forbid. Graphviz compounds it silently: node ids are
 * global, so a state declared inside two clusters is one node in the first, and
 * no warning is printed.
 *
 * <p><b>The rule.</b> An id is the <em>shortest dot-separated suffix of the
 * qualified name that is unique</em> among the machine's states. Uncollided
 * states therefore keep their bare simple name, which is the overwhelming
 * majority case and leaves existing output byte-identical; only the members of a
 * colliding group lengthen, and only as far as they must:
 *
 * <pre>
 *   probe.Idle, probe.Holder.Idle  ->  "probe.Idle", "Holder.Idle"
 *   a.Foo, b.Foo                   ->  "a.Foo",      "b.Foo"
 *   p.Phase.IDLE, p.Mode.IDLE      ->  "Phase.IDLE", "Mode.IDLE"
 * </pre>
 *
 * A package prefix alone would not separate the first pair — they share a
 * package — which is why the rule walks suffixes of the qualified name rather
 * than prepending the package. The lengthened form doubles as the display label,
 * where it is strictly more informative than the ambiguous bare name.
 *
 * <p>The mapping must be built once per hierarchy and consulted by <em>every</em>
 * producer of a state name — {@link io.sealfsm.extract.StateExtractor}, the
 * transition extractor and its successor resolver alike. Disambiguating ids
 * after the fact cannot work: by then a transition endpoint reads {@code "Idle"}
 * and the information that would tell the two apart is already gone.
 */
public final class StateNaming {

    /** Identity naming for callers with no hierarchy in hand (tests, pure-IR tools). */
    public static final StateNaming EMPTY = new StateNaming(Map.of());

    private final Map<String, String> idByQualifiedName;

    private StateNaming(Map<String, String> idByQualifiedName) {
        this.idByQualifiedName = idByQualifiedName;
    }

    /**
     * Build the naming for one machine.
     *
     * @param qualifiedNames every state's qualified name — permitted subtypes,
     *                       nested composites, and enum constants spelled
     *                       {@code Owner.CONSTANT}. Must be the complete set: a
     *                       name left out cannot participate in collision
     *                       detection and would silently keep an ambiguous id.
     */
    public static StateNaming of(Collection<String> qualifiedNames) {
        // Canonical spelling throughout: ids and collision detection must not
        // depend on whether a nesting level arrived as `.` or as Spoon's `$`.
        Set<String> all = new LinkedHashSet<>();
        for (String qn : qualifiedNames) all.add(canonical(qn));

        // Group by the candidate id everything starts from. A group of one needs
        // no disambiguation, which is why existing output does not move.
        Map<String, List<String>> bySimpleName = new LinkedHashMap<>();
        for (String qn : all) {
            bySimpleName.computeIfAbsent(simpleNameOf(qn), k -> new ArrayList<>()).add(qn);
        }

        Map<String, String> ids = new LinkedHashMap<>();
        Set<String> taken = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> group : bySimpleName.entrySet()) {
            if (group.getValue().size() == 1) {
                String qn = group.getValue().get(0);
                ids.put(qn, group.getKey());
                taken.add(group.getKey());
            }
        }
        for (Map.Entry<String, List<String>> group : bySimpleName.entrySet()) {
            if (group.getValue().size() == 1) continue;
            for (String qn : group.getValue()) {
                ids.put(qn, disambiguate(qn, all, taken));
            }
        }
        return new StateNaming(ids);
    }

    /**
     * The shortest suffix of {@code qn} that no other state's qualified name also
     * ends with, and that no id already claims. Falls back to the full qualified
     * name, which is unique by construction.
     */
    private static String disambiguate(String qn, Set<String> all, Set<String> taken) {
        String[] parts = qn.split("\\.");
        for (int from = parts.length - 1; from >= 0; from--) {
            String candidate = String.join(".", List.of(parts).subList(from, parts.length));
            if (taken.contains(candidate)) continue;
            if (uniqueSuffix(candidate, qn, all)) {
                taken.add(candidate);
                return candidate;
            }
        }
        taken.add(qn);
        return qn;
    }

    /**
     * Is {@code suffix} a suffix of no qualified name other than {@code owner}?
     * Matched on segment boundaries, so {@code Idle} does not count as a suffix of
     * {@code p.NotIdle}.
     */
    private static boolean uniqueSuffix(String suffix, String owner, Set<String> all) {
        for (String other : all) {
            if (other.equals(owner)) continue;
            if (other.equals(suffix) || other.endsWith("." + suffix)) return false;
        }
        return true;
    }

    /**
     * The id naming the state with this qualified name.
     *
     * <p>An unknown name falls back to its bare simple name. That keeps a caller
     * asking about a type outside the state set (or an incompletely built model)
     * behaving exactly as it did before this class existed, rather than emitting a
     * qualified name into the middle of a diagram.
     */
    public String idFor(String qualifiedName) {
        if (qualifiedName == null) return null;
        String canonical = canonical(qualifiedName);
        String id = idByQualifiedName.get(canonical);
        return id != null ? id : simpleNameOf(canonical);
    }

    /** The id for an {@code enum} constant state, whose qualified name is {@code Owner.CONSTANT}. */
    public String idForEnumConstant(String ownerQualifiedName, String constant) {
        if (ownerQualifiedName == null || constant == null) return constant;
        return idFor(ownerQualifiedName + "." + constant);
    }

    /** True when at least one state needed a lengthened id — i.e. a collision existed. */
    public boolean hasCollisions() {
        return idByQualifiedName.entrySet().stream()
                .anyMatch(e -> !e.getValue().equals(simpleNameOf(e.getKey())));
    }

    /** The colliding simple names, for diagnostics. Empty when nothing collided. */
    public Set<String> collidingSimpleNames() {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : idByQualifiedName.entrySet()) {
            if (!e.getValue().equals(simpleNameOf(e.getKey()))) out.add(simpleNameOf(e.getKey()));
        }
        return out;
    }

    /**
     * One dotted spelling of a qualified name. Spoon separates a nested type from
     * its owner with {@code $} ({@code p.LcpState$Initial}), so a rule that split
     * on {@code .} alone would read that whole tail as the simple name — every
     * nested state would be renamed, and no collision involving a nested type
     * would ever be detected. Normalising both separators makes the nest path an
     * ordinary segment, which is also the spelling a reader expects to see on a
     * disambiguated state: {@code Holder.Idle}.
     */
    private static String canonical(String qualifiedName) {
        return qualifiedName.replace('$', '.');
    }

    private static String simpleNameOf(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        return dot < 0 ? qualifiedName : qualifiedName.substring(dot + 1);
    }
}
