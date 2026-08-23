package io.sealfsm.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The id-assignment rule, exercised on qualified names directly so the behaviour
 * is pinned independently of Spoon and of any particular fixture.
 */
class StateNamingTest {

    @Test
    void uncollidedNamesKeepTheirBareSimpleName() {
        StateNaming n = StateNaming.of(List.of("p.Red", "p.Green", "p.Yellow"));
        assertEquals("Red", n.idFor("p.Red"));
        assertEquals("Green", n.idFor("p.Green"));
        assertEquals("Yellow", n.idFor("p.Yellow"));
        assertFalse(n.hasCollisions());
    }

    /**
     * The whole point of leaving uncollided names alone: the overwhelming majority
     * of hierarchies have none, and their output must not move.
     */
    @Test
    void aSingleCollisionDoesNotDisturbTheOtherStates() {
        StateNaming n = StateNaming.of(List.of("p.Open", "p.Idle", "p.Legacy$Idle", "p.Closed"));
        assertEquals("Open", n.idFor("p.Open"));
        assertEquals("Closed", n.idFor("p.Closed"));
        assertEquals(Set.of("Idle"), n.collidingSimpleNames());
    }

    /**
     * A nested type beside a top-level one. Both live in the same package, so a
     * package prefix alone would not separate them — the nest path is the
     * discriminator, and the shortest unique suffix finds it.
     */
    @Test
    void aNestedTypeIsSeparatedFromItsTopLevelNamesake() {
        StateNaming n = StateNaming.of(List.of("p.Idle", "p.Legacy$Idle"));
        assertEquals("Legacy.Idle", n.idFor("p.Legacy$Idle"));
        assertEquals("p.Idle", n.idFor("p.Idle"));
        assertTrue(n.hasCollisions());
    }

    /**
     * Spoon spells a nested type {@code Owner$Nested}. A rule that split on
     * {@code .} alone would read that whole tail as the simple name, renaming
     * every nested state in the corpus and detecting no nested collision at all.
     */
    @Test
    void theNestedSeparatorIsCanonicalisedInBothDirections() {
        StateNaming n = StateNaming.of(List.of("p.LcpState$Initial", "p.LcpState$Closed"));
        assertEquals("Initial", n.idFor("p.LcpState$Initial"));
        // Either spelling of the same name must map to the same id.
        assertEquals("Initial", n.idFor("p.LcpState.Initial"));
        assertFalse(n.hasCollisions());
    }

    /** Cross-package collision — legal inside a named module. */
    @Test
    void samelyNamedTypesInDifferentPackagesAreSeparated() {
        StateNaming n = StateNaming.of(List.of("a.Foo", "b.Foo"));
        assertEquals("a.Foo", n.idFor("a.Foo"));
        assertEquals("b.Foo", n.idFor("b.Foo"));
    }

    /**
     * The likeliest shape in practice: two permitted enums that both declare a
     * constant named IDLE. Their constants are child states, so these are two
     * distinct states spelled identically.
     */
    @Test
    void enumConstantsSharingANameAreSeparatedByTheirOwner() {
        StateNaming n = StateNaming.of(List.of(
                "p.Phase", "p.Phase.IDLE", "p.Phase.ACTIVE",
                "p.Mode", "p.Mode.IDLE", "p.Mode.BULK"));
        assertEquals("Phase.IDLE", n.idForEnumConstant("p.Phase", "IDLE"));
        assertEquals("Mode.IDLE", n.idForEnumConstant("p.Mode", "IDLE"));
        // The constants that do not collide are untouched.
        assertEquals("ACTIVE", n.idForEnumConstant("p.Phase", "ACTIVE"));
        assertEquals("BULK", n.idForEnumConstant("p.Mode", "BULK"));
        // As are the owners themselves.
        assertEquals("Phase", n.idFor("p.Phase"));
        assertEquals("Mode", n.idFor("p.Mode"));
    }

    /** Whatever else it does, the mapping must be injective — that is the bug. */
    @Test
    void idsAreAlwaysDistinct() {
        List<String> names = List.of(
                "p.Idle", "p.Legacy$Idle", "q.Idle", "p.Holder$Inner$Idle",
                "p.Phase.IDLE", "p.Mode.IDLE", "p.Other");
        StateNaming n = StateNaming.of(names);
        Set<String> ids = names.stream().map(n::idFor).collect(java.util.stream.Collectors.toSet());
        assertEquals(names.size(), ids.size(), "assigned ids were " + ids);
    }

    /**
     * A name outside the state set falls back to its simple name, so a caller
     * asking about a type that is not a state behaves exactly as it did before
     * this class existed rather than leaking a qualified name into a diagram.
     */
    @Test
    void anUnknownNameFallsBackToItsSimpleName() {
        StateNaming n = StateNaming.of(List.of("p.Red"));
        assertEquals("Helper", n.idFor("p.util.Helper"));
        assertEquals("Nested", n.idFor("p.Owner$Nested"));
        assertEquals("Red", StateNaming.EMPTY.idFor("p.Red"));
    }
}
