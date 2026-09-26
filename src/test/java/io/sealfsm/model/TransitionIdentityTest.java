package io.sealfsm.model;

import io.sealfsm.Analyzer;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What makes two transitions the same transition. A RESOLVED edge is the relation
 * tuple {@code (from, to, event, guard)}: any number of code paths spelling
 * {@code A --e--> B} are one edge. An UNRESOLVED one has no target to compare,
 * so its identity is the program point that computes the unknown successor, and
 * the expression there. Without that, two different unknown successors out of
 * one state compared equal and a set of transitions silently kept one.
 */
class TransitionIdentityTest {

    @Test
    void resolvedEdgesAreTheRelationTuple() {
        Set<Transition> out = new LinkedHashSet<>();
        out.add(Transition.resolved("A", "B", "e", null));
        out.add(Transition.resolved("A", "B", "e", null));
        assertEquals(1, out.size(), "two paths, one edge");
    }

    @Test
    void gapsFromDifferentProgramPointsAreDifferentGaps() {
        Transition first = Transition.unresolved("A", null, null, "return new X()", "F.java:10-24");
        Transition second = Transition.unresolved("A", null, null, "return new Y()", "F.java:40-54");
        assertNotEquals(first, second);
        Set<Transition> out = new LinkedHashSet<>(List.of(first, second));
        assertEquals(2, out.size(), "neither gap may be dropped");
    }

    @Test
    void identicalTextAtTwoProgramPointsIsStillTwoGaps() {
        // `s.onPull()` written in two methods: same text, different places.
        Transition first = Transition.unresolved("<unknown>", null, null, "s.onPull()", "P.java:100-110");
        Transition second = Transition.unresolved("<unknown>", null, null, "s.onPull()", "P.java:300-310");
        assertNotEquals(first, second);
    }

    @Test
    void theSameGapReachedTwiceIsOneGap() {
        Transition first = Transition.unresolved("A", "e", "g", "helper()", "F.java:10-18");
        Transition again = Transition.unresolved("A", "e", "g", "helper()", "F.java:10-18");
        assertEquals(first, again);
        assertEquals(first.hashCode(), again.hashCode());
    }

    @Test
    void aResolvedEdgeCarriesNoOrigin() {
        assertEquals(null, Transition.resolved("A", "B", "e", null).origin());
    }

    /** The fixture: two returns the walker cannot reach, in one state. */
    @Test
    void twoUnreachedReturnsInOneStateAreTwoGaps() {
        Launcher launcher = new Launcher();
        launcher.addInputResource("examples/twingaps");
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        CtModel model = launcher.getModel();
        StateMachine vault = new Analyzer().analyze(model).machines().stream()
                .filter(m -> m.name().equals("Vault")).findFirst().orElseThrow();
        assertEquals(4, vault.transitions().size());
        assertEquals(2, vault.resolvedTransitionCount());
        assertEquals(Set.of("return new twingaps.Open()", "return new twingaps.Alarmed()"),
                vault.transitions().stream()
                        .filter(t -> !t.isResolved() && t.from().equals("Locked"))
                        .map(Transition::note).collect(java.util.stream.Collectors.toSet()),
                "both unknown successors of Locked are recorded, each naming its own return");
    }
}
