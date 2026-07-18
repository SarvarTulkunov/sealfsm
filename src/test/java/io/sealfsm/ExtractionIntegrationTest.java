package io.sealfsm;

import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end extraction over the bundled example fixtures. Requires Spoon on the
 * classpath, so this runs on a normal build machine (it cannot run in a
 * Maven-Central-firewalled sandbox).
 */
class ExtractionIntegrationTest {

    private CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    private StateMachine single(ExtractionResult r) {
        assertEquals(1, r.machines().size(), "expected exactly one machine");
        return r.machines().get(0);
    }

    private boolean hasResolved(StateMachine m, String from, String to) {
        return m.transitions().stream()
                .anyMatch(t -> t.isResolved() && t.from().equals(from) && to.equals(t.to()));
    }

    private boolean hasEventEdge(StateMachine m, String from, String event, String to) {
        return m.transitions().stream()
                .anyMatch(t -> t.isResolved() && t.from().equals(from)
                        && to.equals(t.to()) && event.equals(t.event()));
    }

    private Set<String> stateIds(StateMachine m) {
        return m.allStates().stream().map(io.sealfsm.model.State::id).collect(Collectors.toSet());
    }

    // ---- distributed -------------------------------------------------------

    @Test
    void trafficLightDistributed() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/traffic"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.DISTRIBUTED, m.encoding());
        assertEquals(Set.of("Red", "Green", "Yellow"), stateIds(m));
        assertTrue(hasResolved(m, "Red", "Green"));
        assertTrue(hasResolved(m, "Green", "Yellow"));
        assertTrue(hasResolved(m, "Yellow", "Red"));
        assertEquals("Red", m.initialState().orElse(null));
        // State enumeration is the provably-complete part: nothing unresolved here.
        assertEquals(0, m.unresolvedTransitionCount());
    }

    // ---- centralized -------------------------------------------------------

    @Test
    void doorCentralizedWithGuardsAndSelfLoop() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/door"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED, m.encoding());
        assertEquals(Set.of("Open", "Closed", "Locked"), stateIds(m));

        assertTrue(hasResolved(m, "Open", "Closed"), "Open -> Closed");
        assertTrue(hasResolved(m, "Closed", "Locked"), "Closed -> Locked (guarded)");
        assertTrue(hasResolved(m, "Closed", "Open"), "Closed -> Open (else branch)");
        assertTrue(hasResolved(m, "Locked", "Closed"), "Locked -> Closed (guarded)");
        assertTrue(hasResolved(m, "Locked", "Locked"), "Locked -> Locked (return current self-loop)");

        // The guarded branch must carry a non-null guard.
        Transition guarded = m.transitions().stream()
                .filter(t -> t.from().equals("Closed") && "Locked".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(guarded.guard(), "guarded transition should record its condition");

        assertEquals("Closed", m.initialState().orElse(null));
    }

    @Test
    void turnstileCentralizedWithImperativeIfGuards() {
        // Exercises next-states produced *inside* an `if` within a switch arm:
        //   case Locked l -> { if (event instanceof Coin) yield new Unlocked(); yield l; }
        // A flat return/yield scan drops the guarded branch; the control-flow
        // walker must recover it and attach the if-condition as the guard.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/turnstile"));
        StateMachine m = r.machines().stream()
                .filter(sm -> sm.name().equals("Turnstile"))
                .findFirst().orElseThrow();

        assertEquals(StateMachine.Encoding.CENTRALIZED, m.encoding());
        assertEquals(Set.of("Locked", "Unlocked"), stateIds(m));

        assertTrue(hasResolved(m, "Locked", "Unlocked"), "Locked -> Unlocked (guarded, inside if)");
        assertTrue(hasResolved(m, "Locked", "Locked"), "Locked -> Locked (fall-through self-loop)");
        assertTrue(hasResolved(m, "Unlocked", "Locked"), "Unlocked -> Locked (guarded, inside if)");
        assertTrue(hasResolved(m, "Unlocked", "Unlocked"), "Unlocked -> Unlocked (fall-through self-loop)");

        // The value produced inside the `if` must carry the condition as a guard.
        Transition guarded = m.transitions().stream()
                .filter(t -> t.from().equals("Locked") && "Unlocked".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(guarded.guard(), "if-guarded transition should record its condition");
        assertTrue(guarded.guard().contains("Coin"), "guard should reference the if-condition");

        // The fall-through self-loop must carry the negated guard, keeping the
        // two Locked-origin edges mutually exclusive.
        Transition fallThrough = m.transitions().stream()
                .filter(t -> t.from().equals("Locked") && "Locked".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(fallThrough.guard(), "fall-through branch should record the negated condition");
    }

    @Test
    void reassignedLocalDoesNotEmitFalseSelfLoop() {
        // F1: the next state is computed through a reassigned, root-typed local
        //   case Closed c -> { Gate next = current; if (event instanceof Push) next = new Open(); yield next; }
        // The naive resolver reads `next`'s declared type (the sealed root) and
        // emits a single *unguarded* self-loop, discarding the real `-> Open`
        // edge. The reaching-definitions pass must recover both edges with their
        // mutually-exclusive guards and must NOT emit a blind self-loop.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/localvar"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED, m.encoding());
        assertEquals(Set.of("Open", "Closed"), stateIds(m));

        // Real targets, recovered through the reassignment, are present...
        assertTrue(hasResolved(m, "Closed", "Open"), "Closed -> Open (guarded, via reassigned local)");
        assertTrue(hasResolved(m, "Open", "Closed"), "Open -> Closed (guarded, via reassigned local)");
        // ...as are the else-branch self-loops.
        assertTrue(hasResolved(m, "Closed", "Closed"), "Closed -> Closed (else self-loop)");
        assertTrue(hasResolved(m, "Open", "Open"), "Open -> Open (else self-loop)");

        // The guarded edge carries the if-condition.
        Transition guarded = m.transitions().stream()
                .filter(t -> t.from().equals("Closed") && "Open".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(guarded.guard(), "reassignment target should record its guard");
        assertTrue(guarded.guard().contains("Push"), "guard should reference the if-condition");

        // Every self-loop must be guarded (the else path). A guardless self-loop
        // is exactly the false, confidently-resolved edge F1 is about.
        boolean blindSelfLoop = m.transitions().stream()
                .anyMatch(t -> t.isResolved() && t.from().equals(t.to()) && t.guard() == null);
        assertFalse(blindSelfLoop, "reassigned local must not emit an unguarded self-loop");
    }

    @Test
    void gofContextMutationConvergesWithFunctionalDoor() {
        // F2: the GoF State pattern drives transitions by *mutating* a state field
        //   class Closed { void handle(ctx, e){ if (e instanceof Lock) ctx.setState(new Locked()); else ctx.setState(new Open()); } }
        // rather than returning the next state. The walker must treat a mutator
        // call (ctx.setState(...)) as a transition site, attribute the from-state
        // to the declaring state class, and resolve the argument as the target —
        // recovering the *same* edge set as the return-based examples/door.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/gofcontext"));
        StateMachine m = single(r);

        assertEquals(Set.of("Open", "Closed", "Locked"), stateIds(m));

        // Same resolved edge set as the functional examples/door.
        assertTrue(hasResolved(m, "Closed", "Locked"), "Closed -> Locked (guarded, via setState)");
        assertTrue(hasResolved(m, "Closed", "Open"), "Closed -> Open (else, via setState)");
        assertTrue(hasResolved(m, "Open", "Closed"), "Open -> Closed (via setState)");
        assertTrue(hasResolved(m, "Locked", "Closed"), "Locked -> Closed (guarded, via setState)");
        assertTrue(hasResolved(m, "Locked", "Locked"), "Locked -> Locked (self-loop, setState(this))");

        // The guarded branch carries its if-condition.
        Transition guarded = m.transitions().stream()
                .filter(t -> t.from().equals("Closed") && "Locked".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(guarded.guard(), "mutation target should record its guard");
        assertTrue(guarded.guard().contains("Lock"), "guard should reference the if-condition");

        // The mutation argument was always resolvable — no honest edge is lost.
        assertEquals(0, m.unresolvedTransitionCount(), "every setState target is resolvable");

        // Initial state comes from the context's field initializer, as in door.
        assertEquals("Closed", m.initialState().orElse(null));
    }

    @Test
    void factoryAndDelegateResolvedInterprocedurally() {
        // F3: the transition arms delegate to helpers instead of building the
        // next state inline:
        //   case Open o -> shutOnTurn(current, event);   // returns new Shut() or current
        //   case Shut s -> factoryOpen();                // returns new Open()
        // Bounded inter-procedural summaries (k = 2) must fold each helper's
        // return values into the call site — recovering the guarded edge and the
        // self-loop *through* the delegate — while a target that sits deeper than
        // the budget stays unresolved rather than being guessed.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/factory"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED, m.encoding());
        assertEquals(Set.of("Open", "Shut", "Jammed"), stateIds(m));

        // Resolved *through* helper methods.
        assertTrue(hasResolved(m, "Open", "Shut"), "Open -> Shut via delegate shutOnTurn");
        assertTrue(hasResolved(m, "Open", "Open"), "Open -> Open self-loop via delegate");
        assertTrue(hasResolved(m, "Shut", "Open"), "Shut -> Open via factory factoryOpen");

        // The guard survives the fold.
        Transition guarded = m.transitions().stream()
                .filter(t -> t.from().equals("Open") && "Shut".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(guarded.guard(), "delegated target should keep its guard");
        assertTrue(guarded.guard().contains("Turn"), "guard should reference the delegate's condition");

        // Out-of-budget chain (concrete target 3 hops deep, k = 2): recorded
        // unresolved, never resolved on a guess. This keeps precision honest.
        boolean anyJammedResolved = m.transitions().stream()
                .anyMatch(t -> t.from().equals("Jammed") && t.isResolved());
        assertFalse(anyJammedResolved, "target beyond the depth budget must not be resolved");
        boolean jammedUnresolved = m.transitions().stream()
                .anyMatch(t -> t.from().equals("Jammed") && !t.isResolved());
        assertTrue(jammedUnresolved, "the out-of-budget call must be recorded as unresolved");
    }

    @Test
    void eventAlphabetRecoveredAndEdgesLabelled() {
        // F4: the transition function dispatches on the state and then on a sealed
        // Event parameter. Σ must be recovered in full from Event's permits clause
        // (including the ignored `Skip`), and each switch-over-event arm must label
        // its edge with the matched event so parallel edges no longer collapse.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/eventalphabet"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED, m.encoding());
        assertEquals(Set.of("Stopped", "Playing", "Paused"), stateIds(m));

        // Σ is exact and complete — enumerated from the sealed Event type.
        assertEquals(Set.of("Play", "Pause", "Stop", "Skip"), m.alphabet());

        // `Skip` is in Σ purely by structure: it is folded into every `default`
        // arm and so appears on no edge. This proves Σ comes from the sealed type,
        // not merely from the emitted transitions.
        assertTrue(m.alphabet().contains("Skip"), "ignored event must still be in Σ");
        assertFalse(m.transitions().stream().anyMatch(t -> "Skip".equals(t.event())),
                "the ignored event must not label any edge");

        // Each event-switch arm labels its edge with the matched event.
        assertTrue(hasEventEdge(m, "Playing", "Pause", "Paused"), "Playing --Pause--> Paused");
        assertTrue(hasEventEdge(m, "Playing", "Stop", "Stopped"), "Playing --Stop--> Stopped");
        assertTrue(hasEventEdge(m, "Paused", "Play", "Playing"), "Paused --Play--> Playing");

        // Parallel edges between the same two states no longer collapse: two
        // distinct events drive Stopped -> Playing, so both survive as edges.
        assertTrue(hasEventEdge(m, "Stopped", "Play", "Playing"), "Stopped --Play--> Playing");
        assertTrue(hasEventEdge(m, "Stopped", "Pause", "Playing"), "Stopped --Pause--> Playing");
        long stoppedToPlaying = m.transitions().stream()
                .filter(t -> t.isResolved() && t.from().equals("Stopped") && "Playing".equals(t.to()))
                .count();
        assertEquals(2, stoppedToPlaying, "Play and Pause must be two distinct edges, not collapsed into one");
    }

    // ---- negative control --------------------------------------------------

    @Test
    void shapeSumTypeIsRejected() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/shape"));
        assertTrue(r.isEmpty(), "a plain sum type must not be classified as an FSM");
        boolean explained = r.diagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("sum type"));
        assertTrue(explained, "rejection reason should be reported");
    }

    // ---- combined ----------------------------------------------------------

    @Test
    void allExamplesTogether() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples"));
        List<String> names = r.machines().stream().map(StateMachine::name).toList();
        assertTrue(names.contains("TrafficLight"));
        assertTrue(names.contains("Door"));
        assertFalse(names.contains("Shape"), "Shape must be excluded");
    }
}
