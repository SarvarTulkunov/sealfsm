package io.sealfsm.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One node of an extracted state hierarchy.
 *
 * <p>The nodes come from the compiler-checked {@code permits} clauses, so for a
 * hierarchy <em>classified as a machine</em> the node set is <em>complete by
 * construction</em>. That is the tool's exact claim, and it is distinct from the
 * approximate transition extraction. It is a claim about <b>type branches</b>,
 * not about every class an object may have at run time: a {@code non-sealed}
 * branch ({@link #isOpenBranch()}) can be extended anywhere.
 *
 * <p>Thesis Decision 2 separates three levels, and every metric must say which
 * one it counts:
 * <ul>
 *   <li>a <b>direct branch</b> is a type the root's own {@code permits} clause
 *       names: a machine's {@link StateMachine#topLevelStates()};</li>
 *   <li>a <b>grouping node</b> ({@link #isGrouping()}) has children. It is a
 *       permitted type that is itself sealed, or a permitted {@code enum} whose
 *       constants are its children. It maps onto SCXML's compound {@code <state>}
 *       and a DOT cluster;</li>
 *   <li>an <b>atomic state</b> ({@link #isAtomic()}) is a leaf of that
 *       expansion: a final or {@code non-sealed} type, or an enum constant
 *       ({@link #origin()}).</li>
 * </ul>
 * For {@code sealed interface Phase permits Idle, Speed} with
 * {@code enum Speed { SLOW, FAST }}, the direct branches are {@code {Idle, Speed}},
 * the atomic states are {@code {Idle, Speed.SLOW, Speed.FAST}}, and {@code Speed}
 * is a grouping node that is not counted a second time as an atomic state.
 */
public final class State {

    /** What a node was enumerated from. */
    public enum Origin {
        /** A type named by a {@code permits} clause. */
        TYPE,
        /** A constant of a permitted {@code enum}, closed in exactly the way a {@code permits} clause is. */
        ENUM_CONSTANT
    }

    private final String id;            // simple type name; unique within a machine
    private final String qualifiedName; // fully-qualified type name
    private boolean initial;
    private boolean terminal;
    private boolean declarationUnread;
    private boolean openBranch;
    private final boolean composite;
    private final Origin origin;
    private final List<State> children = new ArrayList<>();

    public State(String id, String qualifiedName, boolean composite) {
        this(id, qualifiedName, composite, Origin.TYPE);
    }

    public State(String id, String qualifiedName, boolean composite, Origin origin) {
        this.id = Objects.requireNonNull(id, "id");
        this.qualifiedName = Objects.requireNonNull(qualifiedName, "qualifiedName");
        this.composite = composite;
        this.origin = Objects.requireNonNull(origin, "origin");
    }

    /** Whether this node is a permitted type or a constant of a permitted enum. */
    public Origin origin() {
        return origin;
    }

    /**
     * A leaf of the hierarchical expansion: an atomic state. A grouping node is
     * never also counted as one.
     */
    public boolean isAtomic() {
        return children.isEmpty();
    }

    /** A node with children: a sealed member, or an enum with constants. */
    public boolean isGrouping() {
        return !children.isEmpty();
    }

    /**
     * True for a {@code non-sealed} member. It is one branch, and its own
     * subclasses are not enumerated as separate states: the {@code permits}
     * clause stops at it and the type system does not close it. The exact claim is
     * about the branch, and a subclass with different behaviour is reported as a
     * diagnostic rather than silently merged or dropped.
     */
    public boolean isOpenBranch() {
        return openBranch;
    }

    public void setOpenBranch(boolean openBranch) {
        this.openBranch = openBranch;
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

    /**
     * True when the dispatch matched this state and every path out of it throws
     * (or otherwise produces no successor): an absorbing state with zero outbound
     * edges. Set by {@link StateMachine#markTerminalStates(java.util.Set)}, which
     * deliberately refuses to mark a state the dispatch never mentioned — that is
     * a recall gap, not a terminal state.
     */
    public boolean isTerminal() {
        return terminal;
    }

    public void setTerminal(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isComposite() {
        return composite;
    }

    /**
     * True when this state was enumerated from a {@code permits} reference whose
     * <em>declaration</em> the analysis never read.
     *
     * <p>The state itself is not in doubt — the {@code permits} clause is
     * compiler-checked, which is why enumeration stays exact even here. What is
     * weaker is everything that depends on the state's <em>identity</em>: its
     * membership rests on the qualified name Spoon guessed for that reference
     * rather than on a declaration, so an edge touching it was matched by a
     * guessed spelling. Measured, that guess is conservative rather than
     * fabricating — Spoon honours an explicit import, and degrades to a bare
     * simple name under a wildcard one, and neither matches the permits spelling,
     * so the edge is declined rather than invented. But "declined rather than
     * invented" is a property worth carrying in the output instead of asserting
     * in prose, because a reader of a diagram or a recall table cannot otherwise
     * tell these edges from the ones resolved against a declaration.
     *
     * <p>Also, and separately: a composite state can never carry this flag. Its
     * children come from the declaration, so an unread member is always a leaf as
     * far as the tool can see — which is itself part of what is lost.
     */
    public boolean isDeclarationUnread() {
        return declarationUnread;
    }

    public void setDeclarationUnread(boolean declarationUnread) {
        this.declarationUnread = declarationUnread;
    }

    public List<State> children() {
        return Collections.unmodifiableList(children);
    }

    public void addChild(State child) {
        children.add(Objects.requireNonNull(child));
    }

    /**
     * Depth-first flattening of a top-level state list: every state, then its
     * children, recursively.
     *
     * <p>One implementation, because two things need it — {@link StateMachine} and
     * {@link Candidate} — and a machine's state set and a candidate's are compared
     * against each other by the completeness test. Two spellings of "flatten"
     * would make that comparison a comparison of two traversals rather than of two
     * state sets.
     */
    public static List<State> flatten(List<State> topLevel) {
        List<State> out = new ArrayList<>();
        for (State s : topLevel) collectInto(s, out);
        return out;
    }

    /**
     * The atomic states of a hierarchy: the leaves of the expansion, each
     * counted once. A type permitted by two sealed branches appears under both of
     * them in the tree, and it is one state, not two (see
     * {@code StateExtractor.Result#overlaps}).
     */
    public static List<State> atomic(List<State> topLevel) {
        return distinct(flatten(topLevel).stream().filter(State::isAtomic).toList());
    }

    /** The grouping nodes of a hierarchy, each counted once. */
    public static List<State> composites(List<State> topLevel) {
        return distinct(flatten(topLevel).stream().filter(State::isGrouping).toList());
    }

    private static List<State> distinct(List<State> states) {
        return List.copyOf(new java.util.LinkedHashSet<>(states));
    }

    private static void collectInto(State s, List<State> out) {
        out.add(s);
        for (State c : s.children()) collectInto(c, out);
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
        return "State{" + id + (composite ? ", composite" : "") + (initial ? ", initial" : "")
                + (terminal ? ", terminal" : "")
                + (origin == Origin.ENUM_CONSTANT ? ", enum-constant" : "")
                + (openBranch ? ", open-branch" : "")
                + (declarationUnread ? ", declaration-unread" : "") + '}';
    }
}
