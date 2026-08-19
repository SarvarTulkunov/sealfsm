package io.sealfsm.analyze;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, normalized boolean IR over atomic predicates (finding F5). Guards
 * elsewhere in the tool are opaque source strings — perfect for an SCXML
 * {@code cond} attribute, but useless for reasoning. This IR lets two guards be
 * <em>compared</em>, so the analyzer can flag two real defects that string
 * guards hide: overlapping guards from the same source state (possible
 * nondeterminism) and guards that do not cover their domain (possible missing
 * transition).
 *
 * <p>The IR is kept in <b>negation normal form</b>: there is no {@code Not}
 * node; negation is folded into the literals ({@link Cmp}/{@link Inst}/{@link
 * Opaque}) when a guard is parsed, so reasoning only ever walks {@code And} /
 * {@code Or} over literals. Parsing ({@link GuardParser}) exploits the fact that
 * the tool <em>emits</em> guards in a known shape — atoms joined by {@code
 * " && "} and negated by {@code "!(...)"} — so a full Java expression parser is
 * unnecessary; anything it cannot model becomes an {@link Opaque} literal and is
 * treated conservatively (never a false claim of overlap).
 *
 * <p>This is deliberately <em>not</em> an SMT solver. It reasons about single
 * numeric variables via interval feasibility and about {@code instanceof}
 * predicates via the closed-world assumption of a sealed hierarchy (a value has
 * exactly one concrete type). That syntactic, per-predicate reasoning catches
 * the common cases the finding targets; anything richer stays conservative.
 */
public sealed interface Guard
        permits Guard.Tru, Guard.Cmp, Guard.Inst, Guard.Opaque, Guard.And, Guard.Or {

    /** Comparison operators over a numeric literal. */
    enum Op {
        LT, LE, GT, GE, EQ, NE;

        /** The operator of the negated predicate ({@code !(x >= c)} is {@code x < c}). */
        Op negate() {
            return switch (this) {
                case LT -> GE;
                case LE -> GT;
                case GT -> LE;
                case GE -> LT;
                case EQ -> NE;
                case NE -> EQ;
            };
        }
    }

    /** The always-true guard (an absent condition). */
    record Tru() implements Guard {}

    /** {@code var op value} — a comparison of a variable against a numeric constant. */
    record Cmp(String var, Op op, double value) implements Guard {}

    /** {@code var instanceof type} (or its negation when {@code neg}). */
    record Inst(String var, String type, boolean neg) implements Guard {}

    /** A predicate the IR does not model; {@code neg} records an outer negation. */
    record Opaque(String text, boolean neg) implements Guard {}

    /** Conjunction. An empty {@code And} is vacuously true. */
    record And(List<Guard> parts) implements Guard {}

    /** Disjunction. An empty {@code Or} is vacuously false. */
    record Or(List<Guard> parts) implements Guard {}

    // ---- construction & normalization ----------------------------------------

    /** True when this literal is atomic (not a compound {@code And}/{@code Or}/{@code Tru}). */
    default boolean isLiteral() {
        return this instanceof Cmp || this instanceof Inst || this instanceof Opaque;
    }

    /**
     * Negation-normal-form negation: push the negation down to the literals so
     * the result never contains a dedicated {@code Not} node.
     */
    static Guard negate(Guard g) {
        return switch (g) {
            case Tru t -> new Or(List.of());                 // ¬true = false
            case Cmp c -> new Cmp(c.var(), c.op().negate(), c.value());
            case Inst i -> new Inst(i.var(), i.type(), !i.neg());
            case Opaque o -> new Opaque(o.text(), !o.neg());
            case And a -> new Or(mapNegate(a.parts()));       // De Morgan
            case Or o -> new And(mapNegate(o.parts()));
        };
    }

    private static List<Guard> mapNegate(List<Guard> parts) {
        List<Guard> out = new ArrayList<>(parts.size());
        for (Guard p : parts) out.add(negate(p));
        return out;
    }
}
