package io.sealfsm.extract;

import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.model.State;
import io.sealfsm.model.StateNaming;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a sealed hierarchy into the complete set of {@link State}s. Because the
 * states come directly from the compiler-checked {@code permits} clauses, this
 * enumeration is exhaustive — the tool's central correctness guarantee.
 *
 * <p>A permitted subtype that is itself sealed becomes a <em>composite</em>
 * state whose children are recursively extracted, mapping onto SCXML nested
 * states.
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
     */
    public record Result(List<State> topLevelStates, StateNaming naming) {
    }

    /** Hierarchy shape read before ids exist; qualified names only. */
    private record Node(String qualifiedName, boolean composite, List<Node> children) {
    }

    /** Returns the top-level states for {@code root} (children nested within). */
    public Result extract(CtType<?> root) {
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
        return new Result(out, naming);
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
                children.add(new Node(qn + "." + constant.getSimpleName(), false, List.of()));
            }
        }
        return new Node(qn, composite, children);
    }

    private static void collectQualifiedNames(Node n, List<String> out) {
        out.add(n.qualifiedName());
        for (Node c : n.children()) collectQualifiedNames(c, out);
    }

    private static State toState(Node n, StateNaming naming) {
        State s = new State(naming.idFor(n.qualifiedName()), n.qualifiedName(), n.composite());
        for (Node c : n.children()) s.addChild(toState(c, naming));
        return s;
    }

    private static List<CtEnumValue<?>> enumConstants(CtType<?> decl) {
        if (!(decl instanceof CtEnum<?> en)) return List.of();
        return List.copyOf(en.getEnumValues());
    }
}
