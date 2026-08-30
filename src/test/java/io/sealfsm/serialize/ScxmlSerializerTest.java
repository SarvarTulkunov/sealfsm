package io.sealfsm.serialize;

import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure checks on SCXML emission, including well-formedness of the output. */
class ScxmlSerializerTest {

    @Test
    void aStateWhoseDeclarationWasNeverReadSaysSoAndStaysWellFormed() {
        // The SCXML consumer sees a <state> with transitions and nothing to suggest
        // that the identity behind it was guessed. The comment is the only place
        // that can be said, and it must not cost well-formedness: an XML comment may
        // not contain a double hyphen, and this text is generated, so the check is
        // worth having rather than assuming.
        StateMachine m = compositeSample();
        m.allStates().stream().filter(s -> s.id().equals("Idle")).findFirst()
                .orElseThrow().setDeclarationUnread(true);
        String xml = new ScxmlSerializer().serialize(m);

        assertTrue(xml.contains("declaration never read"), xml);
        assertDoesNotThrow(() -> DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))),
                "the provenance comment must not break the document");
    }

    private StateMachine compositeSample() {
        StateMachine m = new StateMachine("Phone",
                "examples.phone.Phone", StateMachine.Encoding.POLYMORPHIC);
        State idle = new State("Idle", "examples.phone.Idle", false);
        State active = new State("Active", "examples.phone.Active", true); // composite
        active.addChild(new State("Talking", "examples.phone.Talking", false));
        active.addChild(new State("OnHold", "examples.phone.OnHold", false));
        m.addTopLevelState(idle);
        m.addTopLevelState(active);
        m.addTransition(Transition.resolved("Idle", "Active", "answer", null));
        m.addTransition(Transition.resolved("Active", "Idle", "hangup", null));
        m.addTransition(Transition.unresolved("Active", "transfer", null, "lookup()"));
        m.setInitialState("Idle");
        return m;
    }

    @Test
    void producesWellFormedXml() {
        String xml = new ScxmlSerializer().serialize(compositeSample());
        assertDoesNotThrow(() -> parse(xml), "SCXML output must be well-formed XML");
    }

    @Test
    void terminalLeafBecomesFinalButACompositeNeverDoes() throws Exception {
        // <final> is SCXML's spelling of an absorbing state, and it may contain
        // neither transitions nor children — which fits a terminal leaf exactly.
        // A composite is a different matter: it may have no outbound edge of its
        // own while its children have plenty, and <final> cannot hold them, so
        // emitting one would delete part of the machine from the document.
        StateMachine m = new StateMachine("Latch",
                "examples.barefield.Latch", StateMachine.Encoding.CENTRALIZED_DISPATCH);
        State idle = new State("Idle", "examples.barefield.Idle", false);
        State fired = new State("Fired", "examples.barefield.Fired", false);
        State group = new State("Group", "examples.barefield.Group", true); // composite
        group.addChild(new State("Inner", "examples.barefield.Inner", false));
        m.addTopLevelState(idle);
        m.addTopLevelState(fired);
        m.addTopLevelState(group);
        m.addTransition(Transition.resolved("Idle", "Fired", "TRIGGER", null));
        m.addTransition(Transition.resolved("Inner", "Idle", "RESET", null));
        m.markTerminalStates(java.util.Set.of("Idle", "Fired", "Group", "Inner"));
        m.setInitialState("Idle");

        String xml = new ScxmlSerializer().serialize(m);
        assertDoesNotThrow(() -> parse(xml), "SCXML output must stay well-formed");
        assertTrue(xml.contains("<final id=\"Fired\">"), "the terminal leaf is <final>");
        assertTrue(xml.contains("</final>"));
        assertTrue(xml.contains("<state id=\"Idle\">"), "a state with an exit stays <state>");
        assertTrue(xml.contains("<state id=\"Group\" initial=\"Inner\">"),
                "a composite stays <state> so its children survive");
    }

    @Test
    void nestsCompositeStatesAndMarksInitial() throws Exception {
        String xml = new ScxmlSerializer().serialize(compositeSample());
        var doc = parse(xml);
        assertEquals("Idle", doc.getDocumentElement().getAttribute("initial"));
        // Active is composite -> contains nested <state> children.
        assertTrue(xml.contains("<state id=\"Active\" initial=\"Talking\">"));
        assertTrue(xml.contains("<state id=\"Talking\">"));
    }

    @Test
    void unresolvedTransitionBecomesComment() {
        String xml = new ScxmlSerializer().serialize(compositeSample());
        assertTrue(xml.contains("<!-- unresolved transition on event 'transfer'"));
    }

    @Test
    void selfTransitionOnACompositeIsInternal() throws Exception {
        // Regression: `<transition target="Active"/>` on the composite Active
        // EXITS and re-enters it, so SCXML lands on its initial child (Talking) —
        // meaning a machine sitting in OnHold would silently jump to Talking. The
        // edge came from `stay(this)`, whose meaning is "keep the current child",
        // so it must be an internal transition with no target at all.
        StateMachine m = compositeSample();
        m.addTransition(Transition.resolved("Active", "Active", null, "else").asOtherwise());

        String xml = new ScxmlSerializer().serialize(m);
        var doc = parse(xml);
        assertTrue(xml.contains("internal self-transition"), "the intent should be recorded");

        var transitions = doc.getElementsByTagName("transition");
        boolean found = false;
        for (int i = 0; i < transitions.getLength(); i++) {
            var e = (org.w3c.dom.Element) transitions.item(i);
            if ("else".equals(e.getAttribute("cond"))) {
                found = true;
                assertEquals("", e.getAttribute("target"),
                        "a composite self-loop must carry no target");
            }
        }
        assertTrue(found, "the self-transition must still be emitted");
    }

    @Test
    void selfTransitionOnALeafKeepsItsTarget() throws Exception {
        // The complement: a leaf state has no children to preserve, so its
        // self-loop stays an ordinary targeted transition.
        StateMachine m = compositeSample();
        m.addTransition(Transition.resolved("Idle", "Idle", "ignore", null));
        String xml = new ScxmlSerializer().serialize(m);
        assertTrue(xml.contains("<transition event=\"ignore\" target=\"Idle\"/>"));
    }

    // ---- pseudo-state sources ----------------------------------------------

    /** A machine whose entry point is the synthetic pseudo-state (the F7 shape). */
    private StateMachine entryPseudoStateSample() {
        StateMachine m = new StateMachine("Request",
                "examples.req.Request", StateMachine.Encoding.CENTRALIZED_DISPATCH);
        m.addTopLevelState(new State("Pending", "examples.req.Pending", false));
        m.addTopLevelState(new State("Cancelled", "examples.req.Cancelled", false));
        m.addTransition(Transition.resolved("Pending", "Cancelled", "add", null));
        m.addTransition(Transition.resolved(
                StateMachine.INITIAL_PSEUDO_STATE, "Pending", "subscribe", null));
        m.addTransition(Transition.resolved(
                StateMachine.INITIAL_PSEUDO_STATE, "Cancelled", "add", null));
        m.setInitialState(StateMachine.INITIAL_PSEUDO_STATE);
        return m;
    }

    @Test
    void entryPseudoStateEdgesSurviveSerialization() throws Exception {
        // Regression: transitions were emitted only inside the <state> element
        // matching their source id, so every edge leaving a pseudo-state — the
        // machine-entry edges that name the initial state — was silently dropped.
        // Three edges went in; three must come out.
        String xml = new ScxmlSerializer().serialize(entryPseudoStateSample());
        var doc = parse(xml);
        assertEquals(3, doc.getElementsByTagName("transition").getLength(),
                "no recovered edge may disappear from the output");
    }

    @Test
    void theInitialAttributeResolvesToAnElementThatExists() throws Exception {
        // The document previously declared initial="&lt;initial&gt;" with no such
        // element and an id that is not even a legal XML name — a dangling
        // reference that makes the file useless to a model-based testing tool.
        String xml = new ScxmlSerializer().serialize(entryPseudoStateSample());
        var doc = parse(xml);
        String initial = doc.getDocumentElement().getAttribute("initial");
        assertEquals("_initial", initial, "the pseudo-state id must be a legal XML name");

        var states = doc.getElementsByTagName("state");
        boolean declared = false;
        for (int i = 0; i < states.getLength(); i++) {
            if (initial.equals(((org.w3c.dom.Element) states.item(i)).getAttribute("id"))) {
                declared = true;
            }
        }
        assertTrue(declared, "initial=\"" + initial + "\" must point at a declared <state>");
    }

    @Test
    void anUndeterminedSourceIsRecordedButNotInventedAsAState() throws Exception {
        // An edge whose SOURCE could not be attributed is a gap, not a state.
        // It must stay visible (it depresses recall) without adding a fiction to
        // the model, so it is a comment rather than a <state>.
        StateMachine m = new StateMachine("Gapped",
                "examples.gap.Gapped", StateMachine.Encoding.CENTRALIZED_DISPATCH);
        m.addTopLevelState(new State("Only", "examples.gap.Only", false));
        m.addTransition(Transition.unresolved("<unknown>", null, "coins > 0", "ctx.set(x)"));
        m.setInitialState("Only");

        String xml = new ScxmlSerializer().serialize(m);
        assertDoesNotThrow(() -> parse(xml));
        assertTrue(xml.contains("SOURCE state could not be determined"),
                "the gap must be surfaced in the document");
        assertTrue(xml.contains("ctx.set(x)"), "the offending source text is kept");
        assertEquals(1, parse(xml).getElementsByTagName("state").getLength(),
                "an undetermined source must not become a state");
    }

    private static org.w3c.dom.Document parse(String xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
