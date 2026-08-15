package io.sealfsm.analyze;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a guard's source text into the {@link Guard} IR (finding F5).
 *
 * <p>The tool <em>emits</em> guards in a controlled shape — atoms conjoined with
 * {@code " && "} and negated by wrapping in {@code "!(...)"} (see the {@code
 * merge}/{@code negate} helpers in the extractor and resolver) — so this parser
 * only needs to recognise that structure plus a handful of atomic predicates
 * ({@code instanceof} and numeric comparisons). Anything else becomes an {@link
 * Guard.Opaque} literal, which the reasoner treats conservatively. Parsing is
 * total and never throws: on any surprise it degrades to a single opaque atom.
 */
public final class GuardParser {

    private GuardParser() {}

    /** Comparison operators, longest first so {@code >=} is matched before {@code >}. */
    private static final String[][] OPS = {
            {">=", "GE"}, {"<=", "LE"}, {"==", "EQ"}, {"!=", "NE"}, {">", "GT"}, {"<", "LT"}
    };

    public static Guard parse(String text) {
        if (text == null || text.isBlank()) return new Guard.Tru();
        try {
            return parseExpr(text.trim());
        } catch (RuntimeException e) {
            return new Guard.Opaque(text.trim(), false);
        }
    }

    private static Guard parseExpr(String t) {
        t = stripEnclosingParens(t.trim());

        // Top-level conjunction: split on " && " at paren depth 0.
        List<String> conj = splitTopLevel(t, "&&");
        if (conj.size() > 1) {
            List<Guard> parts = new ArrayList<>(conj.size());
            for (String c : conj) parts.add(parseExpr(c));
            return new Guard.And(parts);
        }

        // Top-level disjunction (guards rarely contain it, but source conditions can).
        List<String> disj = splitTopLevel(t, "||");
        if (disj.size() > 1) {
            List<Guard> parts = new ArrayList<>(disj.size());
            for (String d : disj) parts.add(parseExpr(d));
            return new Guard.Or(parts);
        }

        // Negation: our own combinator only ever emits "!(...)".
        if (t.startsWith("!(") && matchingParenAtEnd(t, 1)) {
            return Guard.negate(parseExpr(t.substring(2, t.length() - 1)));
        }
        return parseAtom(t);
    }

    private static Guard parseAtom(String t) {
        t = stripEnclosingParens(t.trim());

        int io = indexOfTopLevel(t, " instanceof ");
        if (io >= 0) {
            String var = t.substring(0, io).trim();
            String type = t.substring(io + " instanceof ".length()).trim();
            // keep the simple name of the matched type (drop any binding / generics)
            type = type.split("\\s+")[0];
            int dot = type.lastIndexOf('.');
            if (dot >= 0) type = type.substring(dot + 1);
            return new Guard.Inst(var, type, false);
        }

        for (String[] op : OPS) {
            int idx = indexOfTopLevel(t, op[0]);
            if (idx < 0) continue;
            String lhs = t.substring(0, idx).trim();
            String rhs = t.substring(idx + op[0].length()).trim();
            Guard.Op operator = Guard.Op.valueOf(op[1]);
            Double rn = asNumber(rhs);
            if (rn != null) return new Guard.Cmp(lhs, operator, rn);
            Double ln = asNumber(lhs);
            if (ln != null) return new Guard.Cmp(rhs, flip(operator), ln);
            break; // an operator we found but cannot ground → opaque
        }
        return new Guard.Opaque(t, false);
    }

    // ---- small helpers --------------------------------------------------------

    private static Double asNumber(String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The operator seen from the other side: {@code 1 <= x} is {@code x >= 1}. */
    private static Guard.Op flip(Guard.Op op) {
        return switch (op) {
            case LT -> Guard.Op.GT;
            case LE -> Guard.Op.GE;
            case GT -> Guard.Op.LT;
            case GE -> Guard.Op.LE;
            case EQ -> Guard.Op.EQ;
            case NE -> Guard.Op.NE;
        };
    }

    /** Split on {@code sep} only where paren depth is zero. */
    private static List<String> splitTopLevel(String t, String sep) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (depth == 0 && t.startsWith(sep, i)) {
                out.add(t.substring(start, i));
                i += sep.length() - 1;
                start = i + 1;
            }
        }
        out.add(t.substring(start));
        // trim and drop empties
        List<String> cleaned = new ArrayList<>();
        for (String s : out) {
            String v = s.trim();
            if (!v.isEmpty()) cleaned.add(v);
        }
        return cleaned;
    }

    private static int indexOfTopLevel(String t, String needle) {
        int depth = 0;
        for (int i = 0; i + needle.length() <= t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') depth--;
            else if (depth == 0 && t.startsWith(needle, i)) return i;
        }
        return -1;
    }

    /** Remove one fully-enclosing pair of parentheses, if present. */
    private static String stripEnclosingParens(String t) {
        while (t.length() >= 2 && t.charAt(0) == '(' && matchingParenAtEnd(t, 0)) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /** Is the parenthesis opened at {@code open} closed exactly at the last char? */
    private static boolean matchingParenAtEnd(String t, int open) {
        int depth = 0;
        for (int i = open; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == '(') depth++;
            else if (ch == ')') {
                depth--;
                if (depth == 0) return i == t.length() - 1;
            }
        }
        return false;
    }
}
