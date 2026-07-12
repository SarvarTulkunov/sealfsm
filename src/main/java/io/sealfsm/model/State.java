package io.sealfsm.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single state in an extracted finite state machine.
 *
 * <p>Each state corresponds to exactly one permitted subtype of a sealed
 * hierarchy. Because the set of permitted subtypes is closed and compiler
 * verified, the set of {@code State} objects produced for a machine is
 * <em>provably complete</em> — this is the central correctness guarantee of
 * the tool and is distinct from the (approximate) transition extraction.
 *
 * <p>A state is {@link #isComposite() composite} when its own type is itself
 * sealed. In that case it acts as a super-state and its {@link #children()}
 * hold the nested sub-states, which maps directly onto SCXML's nested
 * {@code <state>} elements.
 */
public final class State {

    private final String id;            // simple type name; unique within a machine
    private final String qualifiedName; // fully-qualified type name
    private boolean initial;
    private final boolean composite;
    private final List<State> children = new ArrayList<>();

    public State(String id, String qualifiedName, boolean composite) {
        this.id = Objects.requireNonNull(id, "id");
        this.qualifiedName = Objects.requireNonNull(qualifiedName, "qualifiedName");
        this.composite = composite;
    }

    public String id() {
        return id;
    }

    public String qualifiedName() {
        return qualifiedName;
    }

    public boolean isInitial() {
        return initial;
    }

    public void setInitial(boolean initial) {
        this.initial = initial;
    }

    public boolean isComposite() {
        return composite;
    }

    public List<State> children() {
        return Collections.unmodifiableList(children);
    }

    public void addChild(State child) {
        children.add(Objects.requireNonNull(child));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof State other)) return false;
        return qualifiedName.equals(other.qualifiedName);
    }

    @Override
    public int hashCode() {
        return qualifiedName.hashCode();
    }

    @Override
    public String toString() {
        return "State{" + id + (composite ? ", composite" : "") + (initial ? ", initial" : "") + '}';
    }
}
