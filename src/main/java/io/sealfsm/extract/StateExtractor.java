package io.sealfsm.extract;

import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.model.State;
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
 */
public final class StateExtractor {

    /** Returns the top-level states for {@code root} (children nested within). */
    public List<State> extractStates(CtType<?> root) {
        List<State> out = new ArrayList<>();
        for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(root)) {
            State s = toState(ref, new LinkedHashSet<>());
            if (s != null) out.add(s);
        }
        return out;
    }

    private State toState(CtTypeReference<?> ref, Set<String> seen) {
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

        State state = new State(ref.getSimpleName(), qn, composite);
        if (sealedComposite) {
            for (CtTypeReference<?> childRef : SpoonCompat.permittedTypes(decl)) {
                State child = toState(childRef, seen);
                if (child != null) state.addChild(child);
            }
        } else {
            for (CtEnumValue<?> constant : constants) {
                state.addChild(new State(constant.getSimpleName(),
                        qn + "." + constant.getSimpleName(), false));
            }
        }
        return state;
    }

    private static List<CtEnumValue<?>> enumConstants(CtType<?> decl) {
        if (!(decl instanceof CtEnum<?> en)) return List.of();
        return List.copyOf(en.getEnumValues());
    }
}
