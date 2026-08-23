package io.sealfsm.detect;

import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Root discovery, and the half of it that is only half a decision: a sealed
 * permitted subtype is withheld from the root list because its parent claims it
 * as a composite state, and that claim is only good if the parent turns out to
 * be a machine — which this detector runs too early to know.
 */
class SealedHierarchyDetectorTest {

    private final SealedHierarchyDetector detector = new SealedHierarchyDetector();

    private CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    private CtType<?> type(CtModel model, String qualifiedName) {
        return model.getAllTypes().stream()
                .flatMap(t -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(t), t.getNestedTypes().stream()))
                .filter(t -> t.getQualifiedName().equals(qualifiedName))
                .findFirst().orElseThrow(() -> new AssertionError("no such type: " + qualifiedName));
    }

    private Set<String> qualifiedNames(List<CtType<?>> types) {
        return types.stream().map(CtType::getQualifiedName).collect(Collectors.toSet());
    }

    @Test
    void nestedSealedSubtypesAreNotOfferedAsRoots() {
        CtModel model = modelOf("examples/nestedroots");
        Set<String> roots = qualifiedNames(detector.findSealedRoots(model));

        assertTrue(roots.contains("nestedroots.Message"));
        assertTrue(roots.contains("nestedroots.Envelope"));
        assertTrue(roots.contains("nestedroots.Node"));
        // Withheld: each is permitted by one of the roots above.
        assertFalse(roots.contains("nestedroots.Body"), "Body is permitted by Message");
        assertFalse(roots.contains("nestedroots.Contents"), "Contents is permitted by Envelope");
        assertFalse(roots.contains("nestedroots.Branch"), "Branch is permitted by Node");
    }

    @Test
    void withheldHierarchiesAreOfferedBackForReconsideration() {
        CtModel model = modelOf("examples/nestedroots");

        assertEquals(Set.of("nestedroots.Body"),
                qualifiedNames(detector.permittedSealedSubtypes(type(model, "nestedroots.Message"))));
        assertEquals(Set.of("nestedroots.Contents"),
                qualifiedNames(detector.permittedSealedSubtypes(type(model, "nestedroots.Envelope"))));
        assertEquals(Set.of("nestedroots.Branch"),
                qualifiedNames(detector.permittedSealedSubtypes(type(model, "nestedroots.Node"))));
    }

    @Test
    void nonSealedPermittedSubtypesAreNotOfferedBack() {
        CtModel model = modelOf("examples/nestedroots");
        // Header/Stamp/Leaf are records, Body/Contents/Branch's own members are
        // records: only a SEALED permitted subtype is a hierarchy of its own.
        assertTrue(detector.permittedSealedSubtypes(type(model, "nestedroots.Body")).isEmpty());
        assertTrue(detector.permittedSealedSubtypes(type(model, "nestedroots.Contents")).isEmpty());
    }

    @Test
    void permittedEnumIsAChildStateNotAWithheldHierarchy() {
        // A permitted enum is exact-but-not-sealed: its constants are already child
        // states of the parent, so it must never be offered back as a root.
        CtModel model = modelOf("examples/valueforms");
        CtType<?> signal = type(model, "valueforms.Signal");
        assertTrue(detector.permittedSealedSubtypes(signal).isEmpty(),
                "the permitted enum Phase is a composite state, not a nested root");
    }

    @Test
    void aFlatHierarchyOffersNothingBack() {
        CtModel model = modelOf("examples/traffic");
        for (CtType<?> root : detector.findSealedRoots(model)) {
            assertTrue(detector.permittedSealedSubtypes(root).isEmpty());
        }
    }
}
