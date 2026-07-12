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

    private StateMachine compositeSample() {
        StateMachine m = new StateMachine("Phone",
                "examples.phone.Phone", StateMachine.Encoding.DISTRIBUTED);
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

    private static org.w3c.dom.Document parse(String xml) throws Exception {
        var f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
