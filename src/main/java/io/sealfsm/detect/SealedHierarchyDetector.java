package io.sealfsm.detect;

import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the <em>root</em> sealed types in a model: sealed types that are not
 * themselves permitted subtypes of another sealed type in scope. Each root is a
 * candidate state machine, to be confirmed by {@link StateMachineClassifier}.
 *
 * <p>Nested sealed types (a permitted subtype that is itself sealed) are not
 * returned as separate roots — they are handled as composite states inside
 * their parent by {@link io.sealfsm.extract.StateExtractor}.
 *
 * <p>That withholding is <em>conditional on the parent turning out to be a
 * machine</em>, which this detector cannot know: it runs before classification.
 * So it is offered back through {@link #permittedSealedSubtypes}, and the caller
 * decides which rejections release it.
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

    /**
     * The permitted subtypes of {@code root} that are themselves sealed — exactly
     * the hierarchies {@link #findSealedRoots} withheld because {@code root}
     * claimed them as composite states.
     *
     * <p>That claim only holds if {@code root} is a machine. When it is not, the
     * child would otherwise be lost with it, never classified and never named in
     * a diagnostic: {@code sealed interface Message permits Header, Body}, with
     * {@code Body} itself a sealed state machine, is an ordinary shape, and
     * nothing downstream would ever look at {@code Body}. Offering it back lets
     * the caller re-enter it as a root in its own right.
     *
     * <p>An {@code enum} constant-set is <em>not</em> offered: an enum is never
     * sealed, and its constants are already exact child states of the parent.
     * Which rejections earn the second look is the caller's decision, not this
     * detector's — see {@link io.sealfsm.Analyzer}.
     */
    public List<CtType<?>> permittedSealedSubtypes(CtType<?> root) {
        List<CtType<?>> out = new ArrayList<>();
        for (CtTypeReference<?> ref : SpoonCompat.permittedTypes(root)) {
            CtType<?> decl = ref.getTypeDeclaration();
            if (decl != null && SpoonCompat.isSealed(decl)) {
                out.add(decl);
            }
        }
        return out;
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
