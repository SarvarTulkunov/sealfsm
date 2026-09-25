package io.sealfsm.extract;

import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.model.State;
import io.sealfsm.model.StateNaming;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Turns a sealed hierarchy into its {@link State} nodes. The nodes come directly
 * from the compiler-checked {@code permits} clauses, so for a hierarchy classified
 * as a machine this enumeration is exhaustive. That is the tool's central exact
 * claim, and it is a claim about TYPE BRANCHES (thesis Decision 2).
 *
 * <p>A permitted subtype that is itself sealed becomes a grouping node whose
 * children are recursively extracted, mapping onto SCXML compound states. A
 * permitted {@code enum} becomes a grouping node whose constants are its children.
 * The exported machine contains this full expansion. What a metric counts is
 * chosen explicitly: {@link io.sealfsm.model.StateMachine#directBranches()},
 * {@link io.sealfsm.model.StateMachine#atomicStates()} or
 * {@link io.sealfsm.model.StateMachine#compositeNodes()}.
 *
 * <p>Two conditions weaken the claim, and both are recorded rather than hidden:
 * <ul>
 *   <li>an <b>open branch</b>, a {@code non-sealed} member, may be extended
 *       anywhere. Its subclasses are not states of their own. The ones visible in
 *       the model are listed in {@link Result#openBranches()}, because a
 *       subclass's behaviour is attributed to no state;</li>
 *   <li>an <b>overlap</b>, a type reachable under two direct branches (it
 *       implements two permitted sealed interfaces). It is one state. Which branch
 *       it "belongs to" is not a question the type system answers, so none is
 *       guessed: {@link Result#overlaps()} names every branch, and the atomic state
 *       set counts it once.</li>
 * </ul>
 *
 * <p>The walk runs in two phases over one shared traversal. The hierarchy is
 * first read into a {@link Node} tree keyed on qualified names — the identity
 * that is actually unique — and only then converted to {@link State}s, once the
 * complete name set is known and a {@link StateNaming} can assign ids that no
 * two states share. The order is forced: an id cannot be chosen for one state
 * without knowing every other state's name, since two permitted subtypes may
 * legally share a simple name.
 */
public final class StateExtractor {

    /**
     * The extracted states together with the naming that produced their ids. The
     * naming is part of the result rather than something a caller rebuilds: every
     * downstream producer of a state name — transition endpoints, the initial
     * state, the dispatched-state set — must agree with the ids assigned here, and
     * a second construction of the "same" mapping is exactly how the two halves
     * would drift apart.
     *
     * @param openBranches {@code non-sealed} members, each with the subclasses of it
     *                     the model contains (empty when no model was supplied)
     * @param overlaps     every type reachable under two or more direct branches,
     *                     mapped to those branches' qualified names
     */
    public record Result(List<State> topLevelStates, StateNaming naming,
                         Map<String, Set<String>> openBranches, Map<String, Set<String>> overlaps) {
        public Result {
            openBranches = Map.copyOf(openBranches);
            overlaps = Map.copyOf(overlaps);
        }

        public Result(List<State> topLevelStates, StateNaming naming) {
            this(topLevelStates, naming, Map.of(), Map.of());
        }
    }

    /** Hierarchy shape read before ids exist; qualified names only. */
    private record Node(String qualifiedName, boolean composite, State.Origin origin, boolean open,
                        List<Node> children) {
    }

    /** Returns the top-level states for {@code root} (children nested within). */
    public Result extract(CtType<?> root) {
        return extract(root, null);
    }

    /**
     * The same, with {@code model} consulted for the subclasses of an open branch,
     * which no {@code permits} clause lists.
     */
    public Result extract(CtType<?> root, CtModel model) {
        List<Node> nodes = new ArrayList<>();
        for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(root)) {
            Node n = toNode(ref, new LinkedHashSet<>());
            if (n != null) nodes.add(n);
        }

        List<String> qualifiedNames = new ArrayList<>();
        for (Node n : nodes) collectQualifiedNames(n, qualifiedNames);
        StateNaming naming = StateNaming.of(qualifiedNames);

        List<State> out = new ArrayList<>();
        for (Node n : nodes) out.add(toState(n, naming));
        return new Result(out, naming, openBranches(nodes, model), overlaps(nodes));
    }

    /**
     * Convenience for callers that only want the states and can rebuild or ignore
     * the naming (debug tools, tests).
     */
    public List<State> extractStates(CtType<?> root) {
        return extract(root).topLevelStates();
    }

    private Node toNode(CtTypeReference<?> ref, Set<String> seen) {
        String qn = ref.getQualifiedName();
        if (!seen.add(qn)) return null; // guard against cycles in malformed input
        CtType<?> decl = ref.getTypeDeclaration();
        boolean sealedComposite = decl != null && SpoonCompat.isSealed(decl);
        // A permitted `enum` is closed in exactly the way a `permits` clause is:
        // its constants are enumerable by the compiler and exhaustive. Treating it
        // as one opaque state would merge distinct states that transitions can
        // name individually (`return Phase.RAMP;`), so its constants become child
        // states — the same treatment a nested sealed type already gets. This
        // keeps state enumeration exact; it does not make it heuristic.
        List<CtEnumValue<?>> constants = enumConstants(decl);
        boolean composite = sealedComposite || !constants.isEmpty();

        List<Node> children = new ArrayList<>();
        if (sealedComposite) {
            for (CtTypeReference<?> childRef : SpoonCompat.permittedTypes(decl)) {
                Node child = toNode(childRef, seen);
                if (child != null) children.add(child);
            }
        } else {
            for (CtEnumValue<?> constant : constants) {
                children.add(new Node(qn + "." + constant.getSimpleName(), false,
                        State.Origin.ENUM_CONSTANT, false, List.of()));
            }
        }
        return new Node(qn, composite, State.Origin.TYPE, SpoonCompat.isNonSealed(decl), children);
    }

    private static void collectQualifiedNames(Node n, List<String> out) {
        out.add(n.qualifiedName());
        for (Node c : n.children()) collectQualifiedNames(c, out);
    }

    private static State toState(Node n, StateNaming naming) {
        State s = new State(naming.idFor(n.qualifiedName()), n.qualifiedName(), n.composite(), n.origin());
        s.setOpenBranch(n.open());
        for (Node c : n.children()) s.addChild(toState(c, naming));
        return s;
    }

    private static List<CtEnumValue<?>> enumConstants(CtType<?> decl) {
        if (!(decl instanceof CtEnum<?> en)) return List.of();
        return List.copyOf(en.getEnumValues());
    }

    /**
     * Every {@code non-sealed} node, anywhere in the expansion, with the
     * subclasses of it that the model contains. A subclass is a type whose
     * declaration is a proper subtype of the branch and which is not itself a node
     * of the hierarchy.
     */
    private static Map<String, Set<String>> openBranches(List<Node> nodes, CtModel model) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        List<Node> open = new ArrayList<>();
        Set<String> members = new LinkedHashSet<>();
        for (Node n : nodes) collectOpen(n, open, members);
        if (open.isEmpty()) return out;
        List<CtType<?>> all = model == null ? List.of() : List.copyOf(model.getAllTypes());
        for (Node n : open) {
            Set<String> subclasses = new TreeSet<>();
            for (CtType<?> t : all) collectSubclasses(t, n.qualifiedName(), members, subclasses);
            out.put(n.qualifiedName(), subclasses);
        }
        return out;
    }

    private static void collectOpen(Node n, List<Node> open, Set<String> members) {
        members.add(n.qualifiedName());
        if (n.open()) open.add(n);
        for (Node c : n.children()) collectOpen(c, open, members);
    }

    private static void collectSubclasses(CtType<?> t, String branch, Set<String> members,
                                          Set<String> out) {
        try {
            if (!members.contains(t.getQualifiedName()) && !t.getQualifiedName().equals(branch)) {
                for (CtTypeReference<?> sup : t.getSuperInterfaces()) {
                    if (isSubtypeNamed(sup, branch)) out.add(t.getQualifiedName());
                }
                CtTypeReference<?> sc = t.getSuperclass();
                if (sc != null && isSubtypeNamed(sc, branch)) out.add(t.getQualifiedName());
            }
            for (CtType<?> nested : t.getNestedTypes()) collectSubclasses(nested, branch, members, out);
        } catch (Throwable ignored) {
            // an unreadable type contributes nothing; the branch is still reported as open
        }
    }

    /** Is {@code ref} the branch, or a type that itself descends from it? */
    private static boolean isSubtypeNamed(CtTypeReference<?> ref, String branch) {
        if (branch.equals(ref.getQualifiedName())) return true;
        try {
            CtType<?> decl = ref.getTypeDeclaration();
            if (decl == null) return false;
            for (CtTypeReference<?> sup : decl.getSuperInterfaces()) {
                if (isSubtypeNamed(sup, branch)) return true;
            }
            CtTypeReference<?> sc = decl.getSuperclass();
            return sc != null && isSubtypeNamed(sc, branch);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Every qualified name reachable under two or more direct branches, including
     * a type that is itself a direct branch and also reachable under another. The
     * type system allows it (a record implementing two sealed interfaces that
     * both permit it), and the tool does not choose one of the branches for it.
     */
    private static Map<String, Set<String>> overlaps(List<Node> nodes) {
        Map<String, Set<String>> branchesOf = new LinkedHashMap<>();
        for (Node top : nodes) collectUnder(top, top.qualifiedName(), branchesOf);
        Map<String, Set<String>> out = new LinkedHashMap<>();
        branchesOf.forEach((qn, branches) -> {
            if (branches.size() >= 2) out.put(qn, new TreeSet<>(branches));
        });
        return out;
    }

    private static void collectUnder(Node n, String branch, Map<String, Set<String>> out) {
        out.computeIfAbsent(n.qualifiedName(), k -> new LinkedHashSet<>()).add(branch);
        for (Node c : n.children()) collectUnder(c, branch, out);
    }
}
