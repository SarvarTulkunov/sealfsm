package io.sealfsm.detect;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the <em>root</em> sealed types in a model: sealed types that are not
 * themselves permitted subtypes of another sealed type in scope. Each root is a
 * candidate state machine, to be confirmed by {@link StateMachineClassifier}.
 *
 * <p>Nested sealed types (a permitted subtype that is itself sealed) are
 * deliberately not returned as separate roots — they are handled as composite
 * states inside their parent by {@link io.sealfsm.extract.StateExtractor}.
 */
public final class SealedHierarchyDetector {

    public List<CtType<?>> findSealedRoots(CtModel model) {
        List<CtType<?>> sealed = new ArrayList<>();
        for (CtType<?> type : model.getAllTypes()) {
            collectSealed(type, sealed);
        }

        // Drop any sealed type that is a permitted subtype of another sealed
        // type — those are composite states, not independent machines.
        List<CtType<?>> roots = new ArrayList<>();
        for (CtType<?> candidate : sealed) {
            if (!isPermittedSubtypeOfAnother(candidate, sealed)) {
                roots.add(candidate);
            }
        }
        return roots;
    }

    private void collectSealed(CtType<?> type, List<CtType<?>> out) {
        if (SpoonCompat.isSealed(type)) {
            out.add(type);
        }
        // Recurse into nested/member types so inner sealed hierarchies are seen.
        for (CtType<?> nested : type.getNestedTypes()) {
            collectSealed(nested, out);
        }
    }

    private boolean isPermittedSubtypeOfAnother(CtType<?> candidate, List<CtType<?>> sealed) {
        String candidateQn = candidate.getQualifiedName();
        for (CtType<?> other : sealed) {
            if (other == candidate) continue;
            boolean permitted = SpoonCompat.permittedTypes(other).stream()
                    .anyMatch(ref -> candidateQn.equals(ref.getQualifiedName()));
            if (permitted) return true;
        }
        return false;
    }
}
