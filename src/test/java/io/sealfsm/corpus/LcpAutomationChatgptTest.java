package io.sealfsm.corpus;

import io.sealfsm.Analyzer;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Corpus example lcp_automation_chatgpt -- CORPUS_PROTOCOL.md, split: dev.
 *
 * This is, as of the 2026-08-23 run, the only example directory under examples/
 * that is protocol-conformant (carries meta.json, PROVENANCE.md and oracle/);
 * every other examples/NAME/ directory is a pre-protocol development fixture
 * and is not corpus evidence under section 2 of the protocol.
 *
 * Expected values are read directly from oracle/states.txt and
 * oracle/transitions.tsv rather than embedded here, so this test cannot drift
 * from the frozen oracle: the oracle is the only source of truth for what
 * correct means for this example, and re-typing it inline would let the two
 * disagree silently.
 *
 * The oracle event column uses RFC 1661 tokens verbatim, including the literal
 * plus/minus suffixes that cannot appear in a Java identifier (see
 * oracle/ORACLE.md, "Event normalisation for comparison"). This test applies
 * exactly the mapping that file specifies (trailing plus maps to _PLUS,
 * trailing minus maps to _MINUS) and no other respelling.
 *
 * Status as of tool commit 2d397ca: SealFSM reports zero state machines for
 * this fixture ("no transition producer found" for lcpchatgpt.LcpState --
 * neither DispatchCommitDetector nor CarrierTransitionDetector recognises a
 * centralized switch over the hierarchy type whose result is committed as a
 * constructor argument to a non-hierarchy carrier record). See
 * reports/2026-08-23/findings.md, finding centralized-carrier-nondetection.
 * This test therefore fails at the "exactly one machine" assertion below. It
 * is left failing, not disabled and not weakened, per CORPUS_PROTOCOL.md
 * section 9 ("Never assert current buggy behaviour as expected").
 */
class LcpAutomationChatgptTest {

    private static final Path EXAMPLE_DIR = Path.of("examples", "lcp_automation_chatgpt");
    private static final Path JAVA_DIR = EXAMPLE_DIR.resolve("java");
    private static final Path ORACLE_STATES = EXAMPLE_DIR.resolve("oracle").resolve("states.txt");
    private static final Path ORACLE_TRANSITIONS = EXAMPLE_DIR.resolve("oracle").resolve("transitions.tsv");

    private record OracleTransition(String source, String event, String target) {
    }

    private Set<String> readOracleStates() throws IOException {
        Set<String> states = new LinkedHashSet<>();
        for (String line : Files.readAllLines(ORACLE_STATES)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            states.add(t);
        }
        return states;
    }

    private List<OracleTransition> readOracleTransitions() throws IOException {
        List<OracleTransition> out = new ArrayList<>();
        boolean sawHeader = false;
        for (String rawLine : Files.readAllLines(ORACLE_TRANSITIONS)) {
            String t = rawLine.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!sawHeader) {
                sawHeader = true;
                continue;
            }
            String[] cols = rawLine.split("\t", -1);
            if (cols.length < 3) continue;
            out.add(new OracleTransition(cols[0].strip(), cols[1].strip(), cols[2].strip()));
        }
        return out;
    }

    private static String normalizeOracleEvent(String rfcToken) {
        String t = rfcToken.strip();
        if (t.endsWith("+")) return t.substring(0, t.length() - 1).toUpperCase() + "_PLUS";
        if (t.endsWith("-")) return t.substring(0, t.length() - 1).toUpperCase() + "_MINUS";
        return t.toUpperCase();
    }

    private static String normalizeToolEvent(String event) {
        return event == null ? "-" : event.strip().toUpperCase();
    }

    private ExtractionResult run() {
        Launcher launcher = new Launcher();
        launcher.addInputResource(JAVA_DIR.toString());
        launcher.getEnvironment().setComplianceLevel(21);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        CtModel model = launcher.getModel();
        return new Analyzer().analyze(model);
    }

    @Test
    void statesAndTransitionsMatchTheFrozenOracle() throws IOException {
        Set<String> expectedStates = readOracleStates();
        List<OracleTransition> expectedTransitions = readOracleTransitions();

        assertEquals(10, expectedStates.size(), "oracle/states.txt should hold exactly 10 states");
        assertEquals(120, expectedTransitions.size(), "oracle/transitions.tsv should hold exactly 120 rows");

        ExtractionResult result = run();

        assertEquals(1, result.machines().size(),
                "expected exactly one recovered machine for lcpchatgpt.LcpState "
                        + "(meta.json expected_verdict=fsm); diagnostics: " + result.diagnostics());

        StateMachine m = result.machines().get(0);

        Set<String> actualStates = m.allStates().stream().map(State::id).collect(Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>(expectedStates);
        missing.removeAll(actualStates);
        Set<String> extra = new LinkedHashSet<>(actualStates);
        extra.removeAll(expectedStates);
        assertTrue(missing.isEmpty() && extra.isEmpty(),
                "state set mismatch against oracle/states.txt: missing=" + missing + " extra=" + extra);

        List<String> actualTriples = m.transitions().stream()
                .filter(Transition::isResolved)
                .map(t -> t.from() + "\t" + normalizeToolEvent(t.event()) + "\t" + t.to())
                .sorted()
                .collect(Collectors.toList());
        List<String> expectedTriples = expectedTransitions.stream()
                .map(e -> e.source() + "\t" + normalizeOracleEvent(e.event()) + "\t" + e.target())
                .sorted()
                .collect(Collectors.toList());

        assertEquals(expectedTriples, actualTriples,
                "resolved transition multiset differs from oracle/transitions.tsv");
    }
}
