package io.sealfsm.detect.dispatch;

import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtOperatorAssignment;
import spoon.reflect.code.CtReturn;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtYieldStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtExecutable;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <em>Why</em> a dispatch's commit was not proven: the explanation a Tier 3
 * candidate prints, and the one {@code --explain} prints for the same decision
 * (F32).
 *
 * <p>Both used to print one sentence for every site that discriminates the state:
 * "no branch installs a hierarchy value … the exhaustive-fold guard". That is true
 * of a fold and false of three other things that end in the same place, and on
 * real code it filed a real transition dispatch as a fold. Apache Kafka's
 * {@code KafkaRaftClient.maybeTransitionForward} branches into calls whose commit
 * lies five calls away, and nothing in it folds. {@code chaindispatch.Tree}'s
 * branches DO install hierarchy values; they nest them, and the compositional veto
 * rejected them. The sentence was the only report of either.
 *
 * <p>Each site gets exactly one {@link Kind}, the first that holds in this
 * precedence order. The order puts what the branches <em>produce</em> ahead of
 * what they <em>call</em>, and calls ahead of bookkeeping writes:
 * <ol>
 *   <li>{@link Kind#COMPOSES}: a hierarchy value the branches produce nests
 *       another. The veto's own predicate is asked, so the explanation cannot
 *       disagree with the rule that rejected the dispatch.</li>
 *   <li>{@link Kind#UNREAD_POSITION}: the branches produce hierarchy values, but
 *       nothing commits them.</li>
 *   <li>{@link Kind#FOLDS}: the branches produce values whose type is outside the
 *       hierarchy (the codomain: a returning host's return type, a switch
 *       expression's type).</li>
 *   <li>{@link Kind#CALLS}: the branches call methods whose bodies were read one
 *       call deep, and none writes a root-typed field. This does not say there is
 *       no commit, only that none was found within the probe's depth.</li>
 *   <li>{@link Kind#UNREADABLE_CALLS}: the branches call methods whose bodies could
 *       not be read (F11). The candidate names them separately.</li>
 *   <li>{@link Kind#FOLDS} again, from a write to a non-hierarchy field
 *       ({@code label = "dot";}). This is weaker than a codomain, because bookkeeping
 *       writes look the same, so it counts only where no call was probed.</li>
 *   <li>{@link Kind#NOTHING}: none of the above.</li>
 * </ol>
 *
 * <p>Text only. Nothing here is consulted by a verdict: the classifier has
 * already decided that no commit was proven, and this names which evidence was
 * missing. A wrong kind is therefore a wrong sentence, never a wrong machine. That
 * is also why it may use a coarser reading of the arms than the walkers do.
 */
public final class CommitGap {

    private CommitGap() {
    }

    public enum Kind {
        COMPOSES,
        UNREAD_POSITION,
        FOLDS,
        CALLS,
        UNREADABLE_CALLS,
        NOTHING
    }

    /** One site's gap, with the foreign codomain(s) when it is a fold. */
    public record Gap(Kind kind, Set<String> foreignTypes) {
        public Gap {
            foreignTypes = Set.copyOf(foreignTypes);
        }
    }

    /** Classify one discrimination site whose commit was not proven. */
    public static Gap of(DispatchSite site, Set<String> hierarchy, String rootQualifiedName) {
        List<CtExpression<?>> produced = new ArrayList<>();       // values the branches produce
        Set<String> codomains = new LinkedHashSet<>();            // their types, when outside H
        Set<String> writtenForeign = new LinkedHashSet<>();       // non-H assignment targets
        List<CtElement> bodies = new ArrayList<>();
        for (DispatchArm arm : site.arms()) {
            if (arm.body() != null) bodies.add(arm.body());
        }

        CtElement node = site.node();
        if (node instanceof CtSwitchExpression<?, ?> sw) {
            // The switch IS the value: its arms' yields are what it produces, and
            // its own type is the codomain (`label = switch (state)`).
            for (CtYieldStatement y : sw.getElements(new TypeFilter<>(CtYieldStatement.class))) {
                if (y.getParent(CtSwitchExpression.class) == sw && y.getExpression() != null) {
                    produced.add(y.getExpression());
                }
            }
            addForeign(sw.getType(), hierarchy, codomains);
        }
        for (CtElement body : bodies) {
            for (CtReturn<?> r : body.getElements(new TypeFilter<>(CtReturn.class))) {
                if (r.getReturnedExpression() == null || !ownedBy(r, site.host())) continue;
                produced.add(r.getReturnedExpression());
                if (site.host() instanceof CtMethod<?> m) addForeign(m.getType(), hierarchy, codomains);
            }
            for (CtAssignment<?, ?> a : body.getElements(new TypeFilter<>(CtAssignment.class))) {
                if (a instanceof CtOperatorAssignment<?, ?> || !ownedBy(a, site.host())) continue;
                CtExpression<?> target = a.getAssigned();
                if (target == null) continue;
                if (CommitClassifier.ofTarget(target, hierarchy) != null) {
                    if (a.getAssignment() != null) produced.add(a.getAssignment());
                } else {
                    addForeign(target.getType(), hierarchy, writtenForeign);
                }
            }
        }

        List<CtExpression<?>> hierarchyValues = new ArrayList<>();
        for (CtExpression<?> v : produced) {
            CtTypeReference<?> t = v.getType();
            if (t != null && hierarchy.contains(t.getQualifiedName())) hierarchyValues.add(v);
        }
        for (CtExpression<?> v : hierarchyValues) {
            if (CompositionVeto.nestsHierarchyValue(v, hierarchy)) {
                return new Gap(Kind.COMPOSES, Set.of());
            }
        }
        if (!hierarchyValues.isEmpty()) return new Gap(Kind.UNREAD_POSITION, Set.of());
        if (!codomains.isEmpty()) return new Gap(Kind.FOLDS, codomains);

        CommitProbe.Result probe = CommitProbe.of(bodies, hierarchy, rootQualifiedName);
        if (!probe.probed().isEmpty()) return new Gap(Kind.CALLS, Set.of());
        if (!probe.unreadable().isEmpty()) return new Gap(Kind.UNREADABLE_CALLS, Set.of());
        if (!writtenForeign.isEmpty()) return new Gap(Kind.FOLDS, writtenForeign);
        return new Gap(Kind.NOTHING, Set.of());
    }

    /**
     * The sites grouped by kind, one clause per kind, each naming its hosts:
     * the text both the candidate reason and {@code --explain} print.
     */
    public static String summarize(List<DispatchSite> sites, Set<String> hierarchy,
                                   String rootQualifiedName) {
        Map<Kind, Set<String>> hosts = new LinkedHashMap<>();
        Map<Kind, Integer> counts = new LinkedHashMap<>();
        Set<String> folds = new LinkedHashSet<>();
        for (Kind k : Kind.values()) {
            hosts.put(k, new LinkedHashSet<>());
            counts.put(k, 0);
        }
        for (DispatchSite site : sites) {
            Gap g = of(site, hierarchy, rootQualifiedName);
            counts.merge(g.kind(), 1, Integer::sum);
            hosts.get(g.kind()).add(hostName(site));
            if (g.kind() == Kind.FOLDS) folds.addAll(g.foreignTypes());
        }
        List<String> clauses = new ArrayList<>();
        for (Kind k : Kind.values()) {
            int n = counts.get(k);
            if (n == 0) continue;
            clauses.add("at " + n + " site(s) the branches " + phrase(k, folds)
                    + " [" + String.join(", ", hosts.get(k)) + "]");
        }
        return String.join("; ", clauses);
    }

    private static String phrase(Kind k, Set<String> folds) {
        return switch (k) {
            case COMPOSES -> "install hierarchy values that nest another hierarchy value, "
                    + "which the sibling-vs-nested guard reads as building a data structure "
                    + "rather than choosing a successor";
            case UNREAD_POSITION -> "produce hierarchy values in a position no commit rule "
                    + "reads, so no commit is claimed";
            case FOLDS -> "fold into a type outside the hierarchy (" + String.join(", ", folds)
                    + ") (the exhaustive-fold guard: a transition switch and a fold are "
                    + "identical AT the discrimination, and only the codomain separates them)";
            case CALLS -> "call methods, and no method body read one call deep writes a field "
                    + "of the hierarchy's root type (a commit deeper than one call is possible "
                    + "and is not claimed)";
            case UNREADABLE_CALLS -> "call methods whose bodies could not be read";
            case NOTHING -> "neither produce a value nor call a method that could install one";
        };
    }

    /** {@code Type.method} for a method host, for a reader rather than a key. */
    private static String hostName(DispatchSite site) {
        if (site.host() instanceof CtMethod<?> m) {
            CtType<?> declaring = m.getDeclaringType();
            return (declaring == null ? "?" : declaring.getSimpleName()) + "." + m.getSimpleName();
        }
        return String.valueOf(site.locus());
    }

    private static boolean ownedBy(CtElement e, CtElement host) {
        return e.getParent(CtExecutable.class) == host;
    }

    private static void addForeign(CtTypeReference<?> t, Set<String> hierarchy, Set<String> out) {
        if (t == null) return;
        String qn = t.getQualifiedName();
        if (qn == null || hierarchy.contains(qn) || "void".equals(t.getSimpleName())) return;
        out.add(t.getSimpleName());
    }
}
