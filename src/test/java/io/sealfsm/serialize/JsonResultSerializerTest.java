package io.sealfsm.serialize;

import io.sealfsm.model.Candidate;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine-readable result thesis Decision 3 scores against labels. It is
 * pure IR, with no Spoon involved. Checked with a small strict JSON parser so a
 * scorer is never handed a file it cannot read, and checked for the three things
 * the evaluation protocol depends on: every root's outcome, the state levels
 * reported separately (Decision 2), and candidates marked provisional
 * (Decisions 1 and 4).
 */
class JsonResultSerializerTest {

    @Test
    void theResultIsValidJsonCarryingOutcomesLevelsAndProvisionalCandidates() {
        StateMachine m = new StateMachine("Phase", "p.Phase", StateMachine.Encoding.CENTRALIZED_DISPATCH);
        State idle = new State("Idle", "p.Idle", false);
        State speed = new State("Speed", "p.Speed", true);
        speed.addChild(new State("SLOW", "p.Speed.SLOW", false, State.Origin.ENUM_CONSTANT));
        speed.addChild(new State("FAST", "p.Speed.FAST", false, State.Origin.ENUM_CONSTANT));
        m.addTopLevelState(idle);
        m.addTopLevelState(speed);
        m.addTransition(Transition.resolved("Idle", "SLOW", null, "x > \"1\""));
        m.addTransition(Transition.unresolved("SLOW", null, null, "pick(\\t)"));

        Candidate c = new Candidate("Length", "p.Length",
                List.of(new State("Meters", "p.Meters", false), new State("Feet", "p.Feet", false)),
                List.of("CENTRALIZED_SWITCH @ p.Units#toFeet(p.Meters)"), "no caller in the source set",
                Set.of(Candidate.Basis.INSTALLATION_UNSHOWN));

        ExtractionResult r = new ExtractionResult();
        r.addMachine(m);
        r.outcome("p.Phase", ExtractionResult.Outcome.MACHINE, "1 centralized transition function(s)");
        r.addCandidate(c);
        r.outcome("p.Length", ExtractionResult.Outcome.CANDIDATE, "no caller");
        r.outcome("p.Temperature", ExtractionResult.Outcome.CONVERTED, "used as data");
        r.warn("p.Phase", "a\nmulti-line \"quoted\" message");

        Map<?, ?> json = (Map<?, ?>) new Parser(new JsonResultSerializer().serialize(r)).parse();
        assertEquals(1.0, json.get("schema"));

        List<?> outcomes = (List<?>) json.get("outcomes");
        assertEquals(3, outcomes.size());
        assertEquals("CONVERTED", ((Map<?, ?>) outcomes.get(2)).get("outcome"));

        Map<?, ?> machine = (Map<?, ?>) ((List<?>) json.get("machines")).get(0);
        assertEquals(2, ((List<?>) machine.get("directBranches")).size());
        assertEquals(3, ((List<?>) machine.get("atomicStates")).size(), "Idle, SLOW, FAST");
        assertEquals(1, ((List<?>) machine.get("compositeNodes")).size(), "Speed");
        List<?> transitions = (List<?>) machine.get("transitions");
        assertEquals("x > \"1\"", ((Map<?, ?>) transitions.get(0)).get("guard"));
        assertEquals(false, ((Map<?, ?>) transitions.get(1)).get("resolved"));

        Map<?, ?> candidate = (Map<?, ?>) ((List<?>) json.get("candidates")).get(0);
        assertEquals(true, candidate.get("provisional"));
        assertEquals(List.of("INSTALLATION_UNSHOWN"), candidate.get("basis"));
        assertTrue(candidate.containsKey("provisionalAtomicMembers"),
                "a candidate's members are never reported under a machine's state keys");
        assertTrue(!candidate.containsKey("atomicStates"));

        Map<?, ?> diag = (Map<?, ?>) ((List<?>) json.get("diagnostics")).get(0);
        assertEquals("a\nmulti-line \"quoted\" message", diag.get("message"));
    }

    /** A strict JSON reader: enough to prove the file is well formed. */
    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parse() {
            Object v = value();
            ws();
            if (i != s.length()) throw new IllegalStateException("trailing text at " + i);
            return v;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return true; }
            if (s.startsWith("false", i)) { i += 5; return false; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new IllegalStateException("unexpected '" + c + "' at " + i);
            return Double.parseDouble(s.substring(start, i));
        }

        private Map<String, Object> object() {
            Map<String, Object> out = new LinkedHashMap<>();
            expect('{');
            ws();
            if (s.charAt(i) == '}') { i++; return out; }
            while (true) {
                ws();
                String key = string();
                ws();
                expect(':');
                out.put(key, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return out;
            }
        }

        private List<Object> array() {
            List<Object> out = new ArrayList<>();
            expect('[');
            ws();
            if (s.charAt(i) == ']') { i++; return out; }
            while (true) {
                out.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return out;
            }
        }

        private String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c < 0x20) throw new IllegalStateException("raw control character at " + (i - 1));
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> b.append(e);
                    case 'n' -> b.append('\n');
                    case 'r' -> b.append('\r');
                    case 't' -> b.append('\t');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> throw new IllegalStateException("bad escape \\" + e);
                }
            }
        }

        private void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalStateException("expected '" + c + "' at " + i);
            i++;
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }
}
