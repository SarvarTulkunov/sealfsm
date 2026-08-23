package io.sealfsm;

import io.sealfsm.model.CommitForm;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.SuccessorForm;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        assertEquals(StateMachine.Encoding.POLYMORPHIC, m.encoding());
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

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
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

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
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

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
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

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
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

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
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

    @Test
    void overlappingAndNonExhaustiveGuardsAreDiagnosed() {
        // F5: two guarded state-field mutations in the same arm
        //   if (coins >= 1) this.state = new Vending();
        //   if (coins > 0)  this.state = new Idle();
        // do not serialise into exclusive guards (assignments do not terminate
        // the arm), so both edges are emitted with their raw, overlapping guards.
        // The guard IR must flag the overlap (nondeterminism) and the uncovered
        // coins <= 0 (non-exhaustiveness) — while keeping BOTH edges.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/nondeterministic"));
        StateMachine m = single(r);

        assertEquals(Set.of("Idle", "Vending"), stateIds(m));

        // The invariant: diagnostics never remove an edge. Both overlapping edges
        // out of Idle are still present.
        assertTrue(hasResolved(m, "Idle", "Vending"), "Idle -> Vending still present");
        assertTrue(hasResolved(m, "Idle", "Idle"), "Idle -> Idle (guarded self-loop) still present");

        // Nondeterminism WARN for the overlapping guards from Idle.
        boolean nondeterminism = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == ExtractionResult.Severity.WARN
                        && d.message().toLowerCase().contains("nondetermin"));
        assertTrue(nondeterminism, "overlapping guards should raise a nondeterminism WARN");

        // Non-exhaustiveness WARN for the uncovered coins <= 0.
        boolean coverageGap = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == ExtractionResult.Severity.WARN
                        && d.message().toLowerCase().contains("non-exhaustive"));
        assertTrue(coverageGap, "a numeric coverage gap should raise a non-exhaustiveness WARN");
    }

    @Test
    void exhaustiveGuardsAreNotFalselyFlagged() {
        // Precision guard for F5: the door's mutually-exclusive instanceof guards
        //   Closed -> Locked [event instanceof Lock] / Closed -> Open [else]
        // must NOT be reported as nondeterministic or non-exhaustive.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/door"));
        boolean spurious = r.diagnostics().stream()
                .anyMatch(d -> d.severity() == ExtractionResult.Severity.WARN
                        && (d.message().toLowerCase().contains("nondetermin")
                            || d.message().toLowerCase().contains("non-exhaustive")));
        assertFalse(spurious, "exclusive guards must not raise guard-reasoning warnings");
    }

    @Test
    void tryCatchAndLoopBodiesAreDescended() {
        // F6: producers inside try/catch and a loop body were previously dropped.
        //   case Running r -> { try { yield new Done(); } catch (Exception e) { yield new Failed(); } }
        //   case Waiting w -> { while (retry()) { yield new Running(); } yield w; }
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/errorhandling"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of("Running", "Done", "Failed", "Waiting"), stateIds(m));

        // The normal try-body producer.
        assertTrue(hasResolved(m, "Running", "Done"), "Running -> Done (inside try body)");
        // The error transition from the catch, carrying an exception guard.
        assertTrue(hasResolved(m, "Running", "Failed"), "Running -> Failed (inside catch)");
        Transition errorEdge = m.transitions().stream()
                .filter(t -> t.from().equals("Running") && "Failed".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(errorEdge.guard(), "catch producer should carry a guard");
        assertTrue(errorEdge.guard().toLowerCase().contains("exception"),
                "catch guard should mark the transition as exceptional");

        // The loop-body producer.
        assertTrue(hasResolved(m, "Waiting", "Running"), "Waiting -> Running (inside while body)");
    }

    @Test
    void instanceofDispatchAndFunctionalCallablesAreExtracted() {
        // F7: the transition functions are anonymous BiFunctions (not named
        // switch methods), and each dispatches on its root-typed `apply` selector
        // with `if (current instanceof Cancelled)` rather than a switch. The
        // walker must discover the callables by signature, attribute from-states
        // through the instanceof test, treat the residual as machine entry, label
        // edges with the enclosing method, and skip side-effecting actions.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/cancellation"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of("Pending", "Cancelled"), stateIds(m));

        // Required resolved edges, each labelled by the enclosing method.
        assertTrue(hasEventEdge(m, "Pending", "add", "Cancelled"), "Pending --add--> Cancelled");
        assertTrue(hasEventEdge(m, "Cancelled", "add", "Cancelled"), "Cancelled --add--> Cancelled (self-loop)");
        assertTrue(hasEventEdge(m, "Cancelled", "subscribe", "Cancelled"),
                "Cancelled --subscribe--> Cancelled (self-loop)");
        assertTrue(hasEventEdge(m, "Pending", "subscribe", "Pending"),
                "Pending --subscribe--> Pending (self-loop)");

        // Recommended initial-state edges from the entry residual.
        String init = StateMachine.INITIAL_PSEUDO_STATE;
        assertTrue(hasEventEdge(m, init, "add", "Cancelled"), "<initial> --add--> Cancelled");
        assertTrue(hasEventEdge(m, init, "subscribe", "Pending"), "<initial> --subscribe--> Pending");
        assertEquals(init, m.initialState().orElse(null), "initial state is the entry pseudo-state");

        // Every recovered edge is resolved — the instanceof dispatch attributes a
        // real from-state to each producer, so nothing is left undetermined.
        assertEquals(0, m.unresolvedTransitionCount(), "instanceof dispatch resolves every producer");

        // ABSENT: no undetermined-origin edge — the from-state is always attributed.
        assertFalse(m.transitions().stream().anyMatch(t -> "<unknown>".equals(t.from())),
                "no edge may have an undetermined source state");

        // ABSENT: no edge touches a non-hierarchy type. Actions (cb.run(),
        // pending.add(cb)) must never become edges; the only non-state endpoint
        // allowed is the synthetic initial pseudo-state.
        Set<String> allowed = Set.of("Pending", "Cancelled", init);
        boolean foreignEndpoint = m.transitions().stream()
                .anyMatch(t -> !allowed.contains(t.from())
                        || (t.isResolved() && !allowed.contains(t.to())));
        assertFalse(foreignEndpoint, "no edge may touch a non-hierarchy type (actions are not transitions)");

        // The two events are the enclosing method names, recovered into Σ.
        assertTrue(m.alphabet().contains("add") && m.alphabet().contains("subscribe"),
                "enclosing-method event labels populate the alphabet");
    }

    // ---- polymorphic carrier (F8) ------------------------------------------

    @Test
    void tcpPolymorphicCarrierEncoding() {
        // F8: the RFC 9293 TCP machine in the polymorphic State pattern. Each of
        // the 11 permitted subtypes overrides `Transition on(Event)`, and the
        // successor is an ARGUMENT to a carrier factory rather than the returned
        // value:  return Transition.to(new LastAck(), Action.SND_FIN);
        // No method returns TcpState, so the hierarchy-returning recognizers see
        // nothing and the whole machine was previously lost as a "plain sum type".
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/tcp"));

        // `tcp.Event` is Σ, not a state hierarchy: exactly one machine, not two.
        StateMachine m = single(r);
        assertEquals("TcpState", m.name());
        // Dispatch lives in a per-state method, so this is POLYMORPHIC dispatch.
        // The carrier is a property of how the successor is spelled and committed,
        // not of the encoding — the three axes are reported separately.
        assertEquals(StateMachine.Encoding.POLYMORPHIC, m.encoding());
        assertEquals(Set.of(CommitForm.POLY_CARRIER), m.commitForms(),
                "the successor is handed to a carrier, not returned as the hierarchy type");

        // States remain the exact, provably-complete part: the permits clause.
        assertEquals(Set.of("Closed", "Listen", "SynSent", "SynReceived", "Established",
                        "FinWait1", "FinWait2", "CloseWait", "Closing", "LastAck", "TimeWait"),
                stateIds(m));

        // Targets recovered from inside the carrier's argument list, spanning the
        // three-way handshake, both close paths and the simultaneous-close path.
        assertTrue(hasResolved(m, "Closed", "Listen"), "Closed -> Listen (passive OPEN)");
        assertTrue(hasResolved(m, "Closed", "SynSent"), "Closed -> SynSent (active OPEN)");
        assertTrue(hasResolved(m, "Listen", "SynReceived"), "Listen -> SynReceived (rcv SYN)");
        assertTrue(hasResolved(m, "SynSent", "Established"), "SynSent -> Established (rcv SYN,ACK)");
        assertTrue(hasResolved(m, "Established", "CloseWait"), "Established -> CloseWait (rcv FIN)");
        assertTrue(hasResolved(m, "CloseWait", "LastAck"), "CloseWait -> LastAck (CLOSE)");
        assertTrue(hasResolved(m, "FinWait1", "Closing"), "FinWait1 -> Closing (simultaneous close)");
        assertTrue(hasResolved(m, "Closing", "TimeWait"), "Closing -> TimeWait (rcv ACK of FIN)");
        assertTrue(hasResolved(m, "LastAck", "Closed"), "LastAck -> Closed (rcv ACK of FIN)");

        // `Transition.ignore(this)` is a self-loop, not an unresolved target.
        assertTrue(hasResolved(m, "TimeWait", "TimeWait"), "TimeWait self-loop (ignore(this))");
        assertTrue(hasResolved(m, "Established", "Established"), "Established self-loop (ignore(this))");

        // The event is recovered from the guard, not from the method name: `on`
        // labels nothing, but `event == UserCall.CLOSE` and `event instanceof
        // SegmentArrival` do.
        assertTrue(hasEventEdge(m, "CloseWait", "UserCall.CLOSE", "LastAck"),
                "CloseWait --UserCall.CLOSE--> LastAck");
        assertTrue(hasEventEdge(m, "TimeWait", "Timeout.TIME_WAIT_2MSL", "Closed"),
                "TimeWait --Timeout.TIME_WAIT_2MSL--> Closed");
        assertTrue(hasEventEdge(m, "Established", "SegmentArrival", "Closed"),
                "Established --SegmentArrival--> Closed (RST)");

        // A disjunction of event tests is two inputs of Σ, not one compound label:
        //   if (event == UserCall.CLOSE || event == Timeout.USER)
        assertTrue(hasEventEdge(m, "SynSent", "UserCall.CLOSE", "Closed"), "SynSent --CLOSE--> Closed");
        assertTrue(hasEventEdge(m, "SynSent", "Timeout.USER", "Closed"), "SynSent --USER timeout--> Closed");

        // The data predicate stays a guard, separate from the event label.
        Transition rstAbort = m.transitions().stream()
                .filter(t -> t.from().equals("Established") && "Closed".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(rstAbort.guard(), "the RST branch should record its condition");
        assertTrue(rstAbort.guard().contains("rst()"), "guard should be the data test, not the event test");

        // Σ is enumerated from the sealed Event type, expanding enum members into
        // their constants — the granularity the guards actually test.
        assertTrue(m.alphabet().contains("SegmentArrival"), "record event in Σ");
        assertTrue(m.alphabet().contains("UserCall.PASSIVE_OPEN"), "enum constant in Σ");
        assertTrue(m.alphabet().contains("Timeout.RETRANSMISSION"),
                "an event no edge names must still be in Σ");

        // The from-state is the declaring class, so it is never undetermined.
        assertFalse(m.transitions().stream().anyMatch(t -> "<unknown>".equals(t.from())),
                "the declaring class always fixes the source state");
        assertEquals("Closed", m.initialState().orElse(null));

        // Actions (Action.SIGNAL_ABORT, ...) are carrier payload, not states: no
        // edge may touch anything outside the hierarchy.
        Set<String> ids = stateIds(m);
        assertFalse(m.transitions().stream()
                        .anyMatch(t -> !ids.contains(t.from()) || (t.isResolved() && !ids.contains(t.to()))),
                "no edge may touch a non-state type");
    }

    // ---- successor forms (the axis orthogonal to encoding) ------------------

    @Test
    void everySuccessorFormResolvesToAPermittedSubtype() {
        // The successor of a transition may be written in any of several ways, and
        // resolving them is one uniform sub-procedure independent of where dispatch
        // lives. This fixture holds the encoding fixed (per-state methods returning
        // a carrier) and varies only the spelling.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/valueforms"));
        StateMachine m = single(r);

        // A permitted `enum` contributes its constants as states: they are as
        // compiler-checked-exhaustive as a permits clause, and transitions name
        // them individually, so collapsing them into one state would merge
        // genuinely distinct states.
        assertEquals(Set.of("Idle", "Armed", "Firing", "Phase", "RAMP", "PEAK"), stateIds(m));

        // new Firing(), held in a local and returned through the carrier.
        assertTrue(hasResolved(m, "Armed", "Firing"), "Armed -> Firing (local variable)");
        // A singleton declared with the CONCRETE state as its type.
        assertTrue(hasResolved(m, "Idle", "Armed"), "Idle -> Armed (singleton, concrete-typed)");
        // A singleton declared as the abstract ROOT: only its initializer pins it.
        // Reading the declared type instead would have produced a false self-loop.
        assertTrue(hasResolved(m, "Firing", "Idle"), "Firing -> Idle (singleton, root-typed)");
        assertTrue(hasResolved(m, "Phase", "Idle"), "Phase -> Idle (singleton, root-typed)");
        // Enum constants, qualified and bare.
        assertTrue(hasResolved(m, "Armed", "RAMP"), "Armed -> RAMP (qualified enum constant)");
        assertTrue(hasResolved(m, "Phase", "PEAK"), "Phase -> PEAK (bare enum constant)");
        // `this` through the carrier.
        assertTrue(hasResolved(m, "Idle", "Idle"), "Idle self-loop (this)");

        // Each edge records HOW its successor was written.
        assertEquals(Set.of(SuccessorForm.SINGLETON_FIELD, SuccessorForm.ENUM_CONSTANT,
                        SuccessorForm.LOCAL_VARIABLE, SuccessorForm.SELF),
                m.successorForms());

        // No form may be resolved to a state outside the machine.
        Set<String> ids = stateIds(m);
        assertFalse(m.transitions().stream().anyMatch(t -> t.isResolved() && !ids.contains(t.to())),
                "every resolved successor must be a state of this machine");
    }

    @Test
    void aSuccessorComputedByAHelperStaysUnresolved() {
        // `return Step.to(Router.pick(tick));` — the identity of the successor
        // cannot be established without leaving the method. The soundness
        // invariant says record it, do not guess it, and do not drop it.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/valueforms"));
        StateMachine m = single(r);

        assertTrue(m.transitions().stream().anyMatch(t -> t.from().equals("Firing") && !t.isResolved()),
                "the helper-computed successor must be recorded as unresolved");
        assertFalse(m.transitions().stream()
                        .anyMatch(t -> t.from().equals("Firing") && t.isResolved()
                                && ("Armed".equals(t.to()) || "Firing".equals(t.to()))),
                "the helper's own returns must not leak in as resolved edges");
    }

    @Test
    void defaultBranchIsRecordedAsAnOtherwiseEdge() {
        // A fall-through / else with no event test is a legitimate transition that
        // fires when nothing else does. It must be marked as the default edge —
        // distinguishing "fires otherwise" from "no event was recovered" — and
        // never treated as an error or dropped.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/tcp"));
        StateMachine m = single(r);

        Transition fallThrough = m.transitions().stream()
                .filter(t -> t.from().equals("Established") && "Established".equals(t.to()))
                .findFirst().orElseThrow();
        assertTrue(fallThrough.isOtherwise(), "the trailing ignore(this) is the default edge");
        assertNull(fallThrough.event(), "a default edge carries no event");
        assertNotNull(fallThrough.guard(), "it still carries the residual of the guards before it");

        // An edge an event DID select is not a default edge, even though it also
        // sits on a fall-through path.
        Transition evented = m.transitions().stream()
                .filter(t -> "UserCall.CLOSE".equals(t.event()) && t.from().equals("Established"))
                .findFirst().orElseThrow();
        assertFalse(evented.isOtherwise(), "an event-selected edge is not the default edge");

        // Every state has exactly one way out when nothing matches — no state was
        // left with its default branch dropped.
        for (String id : stateIds(m)) {
            assertTrue(m.transitions().stream().anyMatch(t -> t.from().equals(id) && t.isOtherwise()),
                    "state " + id + " should keep its default edge");
        }
    }

    // ---- centralized dispatch: the commit axis ------------------------------
    //
    // The four tests below hold the ENCODING fixed — one switch over the state
    // type, in every case — and vary only where that switch is hosted and how its
    // result is installed. Recognising only the first of them (the pure function)
    // was the false-negative this axis exists to close: the other three are the
    // same automaton, written the way application code actually writes it.

    @Test
    void http2ValueReturnFromAnExternalHost() {
        // Acceptance #1. `StreamStateMachine.next(state, event)` is a static pure
        // function outside the hierarchy: `return switch (state) { case Idle s ->
        // fromIdle(s, event); ... }`, with each arm delegating to a per-state
        // helper. Dispatch is centralized, the commit is a plain value return, and
        // the from-states come from TYPE patterns (`case Idle s`) rather than
        // record patterns — the other half of D1.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/http2-stream-claude"));
        StateMachine m = single(r);

        assertEquals("StreamState", m.name());
        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of(CommitForm.VALUE_RETURN), m.commitForms());
        assertEquals(Set.of("Idle", "ReservedLocal", "ReservedRemote", "Open",
                        "HalfClosedLocal", "HalfClosedRemote", "Closed"), stateIds(m));

        // Σ: the event is a record carrying an enum (`Send(Signal)`), so one input
        // is the PAIR, not the record. Σ is the closed-world product of the two
        // levels — exactly as many symbols as the flat-constant spelling in #2.
        assertEquals(Set.of("Send.HEADERS", "Send.PUSH_PROMISE", "Send.END_STREAM",
                        "Send.RST_STREAM", "Recv.HEADERS", "Recv.PUSH_PROMISE",
                        "Recv.END_STREAM", "Recv.RST_STREAM"), m.alphabet());

        // Edges carry the composed symbol, not the bare event family. Labelling
        // these `Send` would merge four distinct inputs into one label and make
        // the two Idle edges look like a nondeterministic conflict.
        assertTrue(hasEventEdge(m, "Idle", "Send.HEADERS", "Open"));
        assertTrue(hasEventEdge(m, "Idle", "Recv.HEADERS", "Open"));
        assertTrue(hasEventEdge(m, "Idle", "Send.PUSH_PROMISE", "ReservedLocal"));
        assertTrue(hasEventEdge(m, "Idle", "Recv.PUSH_PROMISE", "ReservedRemote"));
        assertTrue(hasEventEdge(m, "Open", "Send.END_STREAM", "HalfClosedLocal"));
        assertTrue(hasEventEdge(m, "HalfClosedRemote", "Send.RST_STREAM", "Closed"));
        assertFalse(m.transitions().stream().anyMatch(t -> "Send".equals(t.event())
                        || "Recv".equals(t.event())),
                "an edge must name the input, not the event family it belongs to");

        assertEquals(20, m.transitions().size(), "the complete RFC 9113 relation");
        assertEquals(0, m.unresolvedTransitionCount(), "every successor is constructed inline");

        // With the inputs distinguished there is no conflict left to report. The
        // warnings this used to raise were an artefact of the label, not a property
        // of the machine.
        assertFalse(r.diagnostics().stream()
                        .anyMatch(d -> d.message().toLowerCase().contains("nondetermin")),
                "distinct inputs must not read as overlapping guards");

        // `Closed s -> throw illegal(...)` is an explicit rejection, so Closed is
        // terminal: no edge leaves it, and none is invented.
        assertTerminal(m, "Closed");
        // The event hierarchy is Σ, not a second machine.
        assertEquals(1, r.machines().size(), "StreamEvent is the alphabet, not a state hierarchy");
    }

    @Test
    void http2FieldMutationWithExplicitThis() {
        // Acceptance #2. The SAME RFC 9113 machine, written the way a stateful
        // object writes it: `this.currentState = switch (this.currentState) {...}`.
        // The host takes no hierarchy-typed parameter, so the signature-based
        // recognizer (hierarchy-in / hierarchy-out) never saw it and the entire
        // machine was reported as "not a state machine".
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/http2-stream-gemini"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), m.commitForms());

        // States: exact, from the permits clause.
        assertEquals(Set.of("Idle", "ReservedLocal", "ReservedRemote", "Open",
                        "HalfClosedLocal", "HalfClosedRemote", "Closed"), stateIds(m));

        // Σ: all eight enum constants, exact and closed.
        assertEquals(Set.of("SEND_HEADERS", "RECV_HEADERS", "SEND_PUSH_PROMISE",
                        "RECV_PUSH_PROMISE", "SEND_END_STREAM", "RECV_END_STREAM",
                        "SEND_RST_STREAM", "RECV_RST_STREAM"), m.alphabet());

        // The full RFC 9113 §5.1 transition relation, every edge event-labelled.
        assertTrue(hasEventEdge(m, "Idle", "SEND_PUSH_PROMISE", "ReservedLocal"));
        assertTrue(hasEventEdge(m, "Idle", "RECV_PUSH_PROMISE", "ReservedRemote"));
        assertTrue(hasEventEdge(m, "ReservedLocal", "SEND_HEADERS", "HalfClosedRemote"));
        assertTrue(hasEventEdge(m, "ReservedRemote", "RECV_HEADERS", "HalfClosedLocal"));
        assertTrue(hasEventEdge(m, "Open", "RECV_END_STREAM", "HalfClosedRemote"));
        assertTrue(hasEventEdge(m, "Open", "SEND_END_STREAM", "HalfClosedLocal"));
        assertTrue(hasEventEdge(m, "HalfClosedLocal", "RECV_END_STREAM", "Closed"));

        // A multi-label arm is several inputs of Σ sharing a body, not one edge:
        //   case SEND_HEADERS, RECV_HEADERS -> new Open();
        assertTrue(hasEventEdge(m, "Idle", "SEND_HEADERS", "Open"));
        assertTrue(hasEventEdge(m, "Idle", "RECV_HEADERS", "Open"));
        assertTrue(hasEventEdge(m, "Open", "SEND_RST_STREAM", "Closed"));
        assertTrue(hasEventEdge(m, "Open", "RECV_RST_STREAM", "Closed"));

        // No label may be the ENUM's own name: a constant read carries the enum
        // type, so resolving the label as a type pattern would collapse all eight
        // symbols into the single label "Event".
        assertFalse(m.transitions().stream().anyMatch(t -> "Event".equals(t.event())),
                "an arm must be labelled with the constant it matched, not with the enum type");

        assertEquals(20, m.transitions().size(), "the complete RFC 9113 relation, one edge per input");
        assertEquals(0, m.unresolvedTransitionCount());
        assertEquals("Idle", m.initialState().orElse(null));
        assertTerminal(m, "Closed");
    }

    @Test
    void bareFieldCommitBehavesExactlyLikeExplicitThis() {
        // Acceptance #3. `state = switch (state) {...}; return state;` — the same
        // commit as #2 with the `this.` qualifier omitted. Spoon models a bare
        // field read as a CtFieldRead with an implicit `this` target, so the two
        // must be indistinguishable to the recognizer; this pins that it keys off
        // the resolved TYPE of the assignment target and never off the spelling.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/barefield"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), m.commitForms());
        assertEquals(Set.of("Idle", "Armed", "Fired"), stateIds(m));

        assertTrue(hasEventEdge(m, "Idle", "ARM", "Armed"));
        assertTrue(hasEventEdge(m, "Idle", "RESET", "Idle"));
        assertTrue(hasEventEdge(m, "Armed", "TRIGGER", "Fired"));
        assertTrue(hasEventEdge(m, "Armed", "RESET", "Idle"));
        assertEquals(4, m.transitions().size());
        assertEquals(0, m.unresolvedTransitionCount());
        assertEquals("Idle", m.initialState().orElse(null));

        // D3: `default -> throw reject(e)` is an EXPLICIT rejection. It must
        // produce no edge — not a self-loop, not an unresolved edge — and the
        // thrown-exception helper must not be followed looking for a successor.
        assertFalse(m.transitions().stream()
                        .anyMatch(t -> t.note() != null && t.note().contains("reject")),
                "a throw arm must not be mined for a successor");
        // `Fired` rejects everything, so it is terminal rather than merely unvisited.
        assertTerminal(m, "Fired");
        assertFalse(m.allStates().stream().filter(s -> !s.id().equals("Fired"))
                        .anyMatch(State::isTerminal),
                "only the all-throw state is terminal");
    }

    @Test
    void localAccumulatorKeepsBranchTargetsAndInventsNoSelfLoop() {
        // Acceptance #4 (and D4). The dispatch commits through a hierarchy-typed
        // local, and the arms compute the successor by assigning a SECOND local on
        // several branches:
        //     Phase r; if (s == START) r = new Working(); else r = new Blocked(); yield r;
        // Two traps sit here at once. Resolving `r` from its DECLARED type yields
        // the sealed root, which the "root-typed read means stay put" rule turns
        // into a confident, unguarded self-loop — one invented edge, both real
        // targets lost. Collapsing the branch assignments into one reaching value
        // loses a real target instead. Both are failures of the same kind.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/accumulator"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of(CommitForm.LOCAL_ACCUMULATOR), m.commitForms());
        assertEquals(Set.of("Ready", "Working", "Blocked", "Halted"), stateIds(m));

        // Both branch-assigned targets survive, each under its own guard.
        assertTrue(hasResolved(m, "Ready", "Working"), "Ready -> Working (then branch)");
        assertTrue(hasResolved(m, "Ready", "Blocked"), "Ready -> Blocked (else branch)");
        // Declaration initializer + conditional overwrite: both reach the yield.
        assertTrue(hasResolved(m, "Working", "Blocked"), "Working -> Blocked (overwrite)");
        assertTrue(hasResolved(m, "Working", "Working"), "Working -> Working (initializer survives)");

        for (Transition t : m.transitions()) {
            if (t.isResolved() && t.from().equals("Ready")) {
                assertNotNull(t.guard(), "each branch target keeps the condition that selected it");
            }
        }

        // The self-loop that IS present is a real one — `r = new Working()` inside
        // the Working arm — so it is guarded. A self-loop synthesised from the
        // local's declared type would be unguarded, and there must be none.
        assertFalse(m.transitions().stream()
                        .anyMatch(t -> t.isResolved() && t.from().equals(t.to()) && t.guard() == null),
                "no unguarded self-loop may be synthesised from a reassigned local");
        assertFalse(hasResolved(m, "Ready", "Ready"),
                "the Ready arm assigns no Ready value — that edge would be fabricated");

        // Only the branch whose successor is computed past the inter-procedural
        // budget is unresolved. Recorded, never guessed, never dropped.
        assertEquals(1, m.unresolvedTransitionCount());
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> t.from().equals("Blocked") && !t.isResolved()),
                "the out-of-budget delegation must be recorded as unresolved");
        assertFalse(m.transitions().stream()
                        .anyMatch(t -> t.from().equals("Blocked") && t.isResolved()),
                "a successor beyond the budget must not be resolved on a guess");
    }

    @Test
    void bothHttp2ModelsRecoverTheSameMachine() {
        // The corpus contains RFC 9113 §5.1 written twice, independently, in two
        // different idioms: a pure function over a sealed record-carrying event
        // (#1) and a field-mutating switch over a flat enum event (#2). They are
        // the same automaton, so the tool must recover the same automaton — same
        // states, same |Σ|, same transition relation up to how the inputs are
        // spelled.
        //
        // This is the strongest correctness signal available without hand-written
        // ground truth: two source spellings, one answer. It is also the reason the
        // component labels matter. While an edge was labelled with the event FAMILY
        // (`Send`) rather than the input (`Send.HEADERS`), four distinct inputs
        // collapsed onto one label, this fixture reported 18 edges against the
        // other's 20, and the difference looked like a recall gap between commit
        // forms when it was purely a labelling artefact.
        StateMachine fn = single(new Analyzer().analyze(modelOf("examples/http2-stream-claude")));
        StateMachine mut = single(new Analyzer().analyze(modelOf("examples/http2-stream-gemini")));

        assertEquals(stateIds(mut), stateIds(fn), "same state set");
        assertEquals(mut.alphabet().size(), fn.alphabet().size(), "same |Σ|");
        assertEquals(mut.transitions().size(), fn.transitions().size(), "same edge count");
        assertEquals(mut.initialState(), fn.initialState(), "same initial state");

        // The relation itself, compared modulo the input spelling: `Send.HEADERS`
        // in one model is `SEND_HEADERS` in the other, so normalise to
        // DIRECTION+SIGNAL and require the two edge sets to be equal.
        assertEquals(normalisedEdges(mut), normalisedEdges(fn),
                "the two spellings must yield the same transition relation");

        // Both must find the same absorbing state, from opposite evidence: one
        // rejects in a `case Closed s -> throw`, the other in `case Closed() ->
        // throw`.
        assertTerminal(fn, "Closed");
        assertTerminal(mut, "Closed");

        // The encodings differ — that is the point of having both.
        assertEquals(Set.of(CommitForm.VALUE_RETURN), fn.commitForms());
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), mut.commitForms());
    }

    // ---- F13: a local's identity is not its name ---------------------------

    @Test
    void sameNamedLocalsInDisjointArmsDoNotCondemnEachOther() {
        // Every arm of GateDriver.transition declares its own `next` — the ordinary
        // way a per-arm switch is written, and legal because Java scopes a local to
        // its block. Asking "is `next` reassigned?" by scanning the method for a
        // write to SOME variable of that name answers a question about the
        // spelling: the Ajar arm's accumulator made the other two arms' locals look
        // reassigned, pushing reads that were single assignment onto the
        // reaching-definitions fallback.
        //
        // That fallback does not model exceptional flow, so on the Wedged arm — a
        // single-assignment local read from inside a `try` — it bailed and the edge
        // became `-> ?`. The fixture is ONE RENAME away from 5/5 and 4/5: with the
        // three locals spelled nextA / next / nextC the same source reported every
        // edge. A real transition lost to an unrelated variable's name is a recall
        // gap with no defensible reading.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/scopedlocals"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of(CommitForm.VALUE_RETURN), m.commitForms());
        assertEquals(Set.of("Shut", "Ajar", "Wedged"), stateIds(m));
        assertEquals("Shut", m.initialState().orElse(null));

        assertEquals(5, m.transitions().size());
        assertEquals(0, m.unresolvedTransitionCount(),
                "no edge may be lost to a sibling arm's choice of variable name");

        assertTrue(hasResolved(m, "Shut", "Ajar"));
        assertTrue(hasResolved(m, "Wedged", "Shut"),
                "the try-enclosed read is the edge the name collision dropped");
        assertTrue(hasResolved(m, "Wedged", "Ajar"), "the catch arm's producer");

        // The successor-form axis records how a value was SPELLED, so the
        // degradation was visible there too: routed through the reaching-definitions
        // pass, `Gate next = new Ajar()` reports the initializer's CONSTRUCTION and
        // the local disappears from the axis entirely.
        assertTrue(m.successorForms().contains(SuccessorForm.LOCAL_VARIABLE),
                "a single-assignment local must be attributed to the local, not to its initializer");

        // NEGATIVE CONTROL — the Ajar arm, whose `next` IS reassigned. Deciding
        // reassignment by identity must not turn this into "never written": `next`
        // starts life as the root-typed selector `current`, so a single-assignment
        // reading resolves it to a confident, UNGUARDED self-loop and loses the
        // `new Wedged()` target altogether. That is finding F1. What must survive
        // is the pair of mutually-exclusive guarded edges the source actually
        // encodes.
        assertTrue(hasResolved(m, "Ajar", "Wedged"), "the reassigned target must survive");
        assertTrue(hasResolved(m, "Ajar", "Ajar"), "as must the fall-through self-loop");
        assertTrue(m.transitions().stream()
                        .filter(t -> "Ajar".equals(t.from()))
                        .allMatch(t -> t.guard() != null && !t.guard().isBlank()),
                "both Ajar edges are conditional; an unguarded one is the F1 fabrication");

        // No fallback to name matching was needed anywhere in this model, so the
        // diagnostic that would report a weaker answer must be absent.
        assertFalse(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("decided by NAME")),
                "identity was decidable throughout; nothing may claim otherwise");
    }

    // ---- F9: a helper that cannot return normally is not a producer ---------

    @Test
    void nonReturningHelperYieldsNoEdgeButAHiddenReturnStaysUnresolved() {
        // F9 and its own negative control in one hierarchy. Both helpers return
        // the hierarchy type and both defeat the shallow `collectReturns` walk,
        // which does not descend into a switch — so the two are indistinguishable
        // to the summariser and can only be separated by whether a `return` exists
        // at all.
        //
        // `reject` has none. JLS §8.4.7 already forbids a non-void method whose
        // body can complete normally, so the compiler has PROVEN it always throws
        // or diverges: there is no successor, and the three arms calling it are
        // undefined inputs rather than unresolved targets. This is exact, which is
        // the only reason it may suppress an edge at all.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/nonreturning"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of("Idle", "Armed", "Fired"), stateIds(m));

        assertTrue(hasEventEdge(m, "Idle", "ARM", "Armed"));
        assertTrue(hasEventEdge(m, "Armed", "FIRE", "Fired"));
        assertTrue(hasEventEdge(m, "Armed", "RESET", "Idle"));

        // The three `reject` arms contribute nothing — not an edge, not even an
        // unresolved one. Without F9 these were three `-> ?` edges, making the
        // spelling `default -> reject(s, e)` report a different relation from
        // `default -> throw reject(s, e)` for the same machine.
        assertEquals(5, m.transitions().size(),
                "a helper that cannot return normally is not a transition producer");

        // NEGATIVE CONTROL, and the whole risk of this rule: `escalate` DOES
        // return a state, but only from inside a switch the summariser cannot
        // read. `collectReturns` comes back empty for it exactly as it does for
        // `reject`, so a rule keyed on that emptiness would drop a real target.
        // It must stay UNRESOLVED — recorded, never guessed, never dropped.
        assertEquals(2, m.unresolvedTransitionCount(),
                "a return hidden in a switch is unresolved, not absent");
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> "Idle".equals(t.from()) && "ESCALATE".equals(t.event())
                                && !t.isResolved()),
                "the hidden-return helper must remain an unresolved edge from Idle");

        // F11 — SECOND NEGATIVE CONTROL, and the wider hole of the two.
        // `Objects.requireNonNull(current)` is a JDK method, so Spoon hands back a
        // reflective SHADOW declaration: a real signature with an empty stub body.
        // That body has no `return` for the same reason it has nothing at all — it
        // was never parsed — so its emptiness says nothing about whether the method
        // returns. F9 read it as proof and DELETED this edge, with no unresolved
        // marker anywhere: 5 transitions were reported as 4. The same hole swallows
        // `Optional.orElse(new Fired())` and `map.getOrDefault(k, new Idle())`.
        // F9's licence to suppress comes from JLS §8.4.7 making the conclusion
        // exact; on a body the analysis never read, there is no such licence.
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> "Idle".equals(t.from()) && "HOLD".equals(t.event())
                                && !t.isResolved()),
                "a call into an unread (shadow) body is unresolved, never suppressed");

        // The suppression count must track the three `reject` arms only. If the
        // shadow call were counted too it would read as a fourth proven rejection —
        // a false claim dressed in the diagnostic that exists to make F9 auditable.
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().startsWith("3 call(s) to a helper")),
                "only the three provably-throwing arms may be reported as suppressed");

        // The suppression is reported, so it is reclassified rather than silent —
        // the "never silently dropped" invariant is about visibility, and this
        // keeps the count auditable against the source.
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("cannot return normally")),
                "suppressed arms must be reported, not vanish");

        // Fired is dispatched (`case Fired f -> reject(...)`) and every input it
        // handles throws, so it is genuinely absorbing rather than a recall gap.
        assertTerminal(m, "Fired");
    }

    @Test
    void dhcpRejectionHelperDoesNotManufactureUnresolvedEdges() {
        // The fixture F9 was found on. `DhcpClientStateMachine` guards all eight
        // arms with `default -> invalid(state, event)`, where `invalid` returns
        // DhcpState and always throws. Each of those was an unresolved edge — one
        // per state — reporting 21/29 for a machine that has exactly 21
        // transitions and inflating the denominator with non-transitions.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/dhcp-client-chatgpt"));
        StateMachine m = single(r);

        assertEquals(8, m.topLevelStates().size());
        assertEquals(21, m.transitions().size(), "the relation the source actually defines");
        assertEquals(0, m.unresolvedTransitionCount(),
                "`default -> invalid(...)` is an undefined input, not an unresolved target");

        // Rebinding writes its rejection as a bare `throw` instead, which the D3
        // rule already dropped. After F9 the two spellings agree: neither arm is
        // an edge, so the relation no longer depends on where the throw is written.
        assertTrue(hasEventEdge(m, "Rebinding", "DHCPACK_RECEIVED", "Bound"));
        assertFalse(m.transitions().stream()
                        .anyMatch(t -> "Rebinding".equals(t.from())
                                && "LEASE_EXPIRED".equals(t.event())),
                "an arm that throws is not a transition, however the throw is spelled");
    }

    /** `from --SEND_HEADERS--> to` and `from --Send.HEADERS--> to` normalise alike. */
    private Set<String> normalisedEdges(StateMachine m) {
        return m.transitions().stream()
                .filter(Transition::isResolved)
                .map(t -> t.from() + "|"
                        + String.valueOf(t.event()).toUpperCase().replace('.', '_')
                        + "|" + t.to())
                .collect(Collectors.toSet());
    }

    /** A state marked terminal must in fact have no outbound edge, and vice versa. */
    private void assertTerminal(StateMachine m, String id) {
        State s = m.allStates().stream().filter(st -> st.id().equals(id))
                .findFirst().orElseThrow();
        assertTrue(s.isTerminal(), id + " should be terminal");
        assertFalse(m.transitions().stream().anyMatch(t -> t.from().equals(id)),
                "a terminal state must have no outbound edge");
    }

    // ---- F10: an expression statement is not a produced successor -----------

    @Test
    void ordinaryPlumbingInsideADispatchIsNotATransition() {
        // F10. `walk` ended in a catch-all that handed ANY expression reaching it
        // to the successor resolver. Every call site passes a STATEMENT, so the
        // only thing that catch-all ever saw was an expression statement — and
        // Java discards an expression statement's value (JLS §14.8). A value the
        // language throws away cannot be a committed successor, which makes this
        // exact rather than heuristic: it belongs beside F9 on the
        // compiler-checked side of the line, not with the approximate data-flow.
        //
        // Found on examples/lcp_automation, where a two-line
        // `Objects.requireNonNull` prelude reported a 105-edge RFC 1661 automaton
        // as 115. The corpus had never caught it because no fixture until now
        // contained the bookkeeping real code is full of.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/plumbing"));
        StateMachine m = single(r);

        assertEquals(Set.of("Stopped", "Running", "Jammed"), stateIds(m));

        // Exactly the six edges the two switches encode — two per state.
        assertEquals(6, m.transitions().size(),
                "only the switch arms commit a successor; the plumbing does not");
        assertEquals(0, m.unresolvedTransitionCount(),
                "nothing in this machine is unrecoverable, so no gap may be reported");

        assertTrue(hasResolved(m, "Stopped", "Running"));
        assertTrue(hasResolved(m, "Stopped", "Stopped"));
        assertTrue(hasResolved(m, "Running", "Jammed"));
        assertTrue(hasResolved(m, "Running", "Running"));
        assertTrue(hasResolved(m, "Jammed", "Stopped"));
        assertTrue(hasResolved(m, "Jammed", "Jammed"));

        // The sharpest of the four plumbing statements: `identity(current)` is
        // in-model, hierarchy-typed and trivially summarisable, so F3 WOULD fold
        // it to a self-loop on the selector. Nothing about the expression is
        // unresolvable — only its statement position rules it out. A guard that
        // merely required a hierarchy type (as the carrier walker uses) would
        // still have recorded it, which is why the position is the real rule.
        assertFalse(m.transitions().stream().anyMatch(t -> t.from() == null),
                "a discarded value must not become an edge with no source state");

        // And the failure mode that made this visible: an undetermined SOURCE.
        // `Objects.requireNonNull(event, ...)` produced exactly this in LCP.
        assertFalse(m.transitions().stream()
                        .anyMatch(t -> "<unknown>".equals(t.from()) || "<entry>".equals(t.from())),
                "no pseudo-sourced edge may be manufactured out of plumbing");
    }

    @Test
    void aStatementThatIsTheCommitIsStillClaimed() {
        // NEGATIVE CONTROL for F10, and the whole risk of the rule. `ctx.setState(
        // new Filling())` is an expression statement too. "Ignore expression
        // statements" taken one step too far deletes the entire F2 mutation
        // encoding from the tool; the rule is "ignore expression statements that no
        // commit form claims". Separate hierarchy because F2 runs only as a
        // fallback — beside a VALUE_RETURN machine it never executes and this
        // control would silently assert nothing.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/plumbing-mutation"));
        StateMachine m = single(r);

        assertEquals(Set.of("Empty", "Filling", "Full"), stateIds(m));
        assertEquals(6, m.transitions().size());
        assertEquals(0, m.unresolvedTransitionCount());

        // `drive` — arrow arms of a switch STATEMENT. This is F10's second half:
        // Spoon wraps such an arm in a synthetic CtYieldStatement even though
        // `yield` is illegal outside a switch expression, so the arm reached
        // handleValue as a produced value, where a void setState call resolves to
        // nothing. All three edges were LOST and replaced by `-> ?`. Unwrapping the
        // synthetic yield routes them back through `walk`, where the mutator branch
        // claims them — so F10 raises recall here, it does not only trim.
        assertTrue(hasResolved(m, "Empty", "Filling"), "arrow-arm commit must survive");
        assertTrue(hasResolved(m, "Filling", "Full"), "arrow-arm commit must survive");
        assertTrue(hasResolved(m, "Full", "Empty"), "arrow-arm commit must survive");

        // `pump` — colon arms, a deliberately DISJOINT relation so the two arm
        // spellings stay separately attributable. Were both to encode the same
        // edges the set-valued store would dedup them, and this test could not tell
        // which spelling produced anything.
        assertTrue(hasResolved(m, "Empty", "Full"), "colon-arm commit must survive");
        assertTrue(hasResolved(m, "Filling", "Empty"), "colon-arm commit must survive");
        assertTrue(hasResolved(m, "Full", "Filling"), "colon-arm commit must survive");
    }

    // ---- F12: a guarded arm excludes the arms after it ----------------------

    @Test
    void guardedArmsAreMutuallyExclusiveWithTheArmsBelowThem() {
        // F12, part 2. Arm ORDER is part of the semantics: in
        //   case Timeout t when t.counter() > 0 -> state;
        //   case Timeout t                      -> new Stopped();
        // the second arm runs only when the first guard was false, so the two can
        // never both be enabled. Recording the fall-through arm as an unguarded
        // `true` made GuardAnalysis report nondeterminism the source does not
        // contain — 14 such warnings on examples/lcp_automation, a deterministic
        // RFC 1661 automaton. Same reasoning walkBlock already applies to an `if`
        // with no `else`, carried across sibling arms.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/lcp_automation"));
        StateMachine m = single(r);

        assertTrue(r.diagnostics().stream().noneMatch(d -> d.message().contains("nondeterminism")),
                "a deterministic switch must not be reported as nondeterministic");

        List<Transition> rcr = m.transitions().stream()
                .filter(t -> "Stopped".equals(t.from())
                        && "ReceiveConfigureRequest".equals(t.event()))
                .toList();
        assertEquals(2, rcr.size(), "RCR+ and RCR- are two edges");
        // One carries the guard, the other its negation — never a bare null.
        assertTrue(rcr.stream().allMatch(t -> t.guard() != null),
                "the fall-through arm is guarded by the negation, not unguarded");
        assertTrue(rcr.stream().anyMatch(t -> t.guard().contains("!")),
                "the later arm must carry the negated guard");
    }

    @Test
    void everyGuardSpellingReachesTheEdgeLabel() {
        // The guard-form axis, held against a fixed two-state machine so the only
        // variable is how the `when` clause is written. Spoon 10.4.2 fills
        // CtCase.getGuard() only for a CtBinaryOperator; the other thirteen shapes
        // here are recovered from the arm body. A silently lost guard turns a
        // conditional edge into an unconditional CLAIM, and leaves the F12
        // exclusion nothing to separate it from its fall-through arm with.
        //
        // Three of these were found losing their guard only by enumerating the
        // table: Boxed (a `Boolean` guard failing a check written for the
        // primitive), Cast (a cast is not its own node, so `(Boolean) t.o()`
        // reported the invocation's own Object type), and Sw (recovered, but its
        // five-line source reached the DOT label with the newlines intact —
        // Graphviz accepts that, so it failed silently).
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/guardforms"));
        StateMachine m = single(r);

        Map<String, Transition> byEvent = new HashMap<>();
        for (Transition t : m.transitions()) {
            if (t.event() != null && "Busy".equals(t.to())) byEvent.put(t.event(), t);
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("Inv", "t.flag()");
        expected.put("Unary", "!t.flag()");
        expected.put("Binary", "t.n() > 10");
        expected.put("Conj", "&&");
        expected.put("Disj", "||");
        expected.put("Boxed", "t.flag()");            // java.lang.Boolean, not boolean
        expected.put("Bound", "flag");                // pattern-binding read
        expected.put("Inst", "instanceof");
        expected.put("Ternary", "?");
        expected.put("Arr", "t.flags()[0]");
        expected.put("NegBin", "!(t.n() > 3)");
        expected.put("Static", "positive");
        expected.put("Chain", "isEmpty()");
        expected.put("Lib", "equals");
        expected.put("Sw", "switch");                 // guard that is itself a switch
        expected.put("Paren", "t.flag()");
        expected.put("Deep", "&&");
        expected.put("Lambda", "anyMatch");
        expected.put("Cast", "Boolean");              // cast hangs off the expression
        expected.put("Nest", "on");                   // nested record pattern binding

        for (Map.Entry<String, String> e : expected.entrySet()) {
            Transition t = byEvent.get(e.getKey());
            assertNotNull(t, "no edge for guard shape " + e.getKey());
            assertNotNull(t.guard(), "guard lost for shape " + e.getKey());
            assertTrue(t.guard().contains(e.getValue()),
                    e.getKey() + ": expected guard to contain '" + e.getValue()
                            + "' but was '" + t.guard() + "'");
        }

        // Guard text reaches a DOT label and an SCXML cond attribute, so it must be
        // one line however the source was formatted.
        for (Transition t : m.transitions()) {
            if (t.guard() != null) {
                assertEquals(1, t.guard().lines().count(),
                        "guard text must be single-line: " + t.guard());
            }
        }
    }

    @Test
    void realGuardOverlapIsStillReported() {
        // NEGATIVE CONTROL for F12, and the whole risk of it: the exclusion rule
        // must not become a blanket "stop reporting nondeterminism". It applies
        // ONLY to sibling arms of one switch, where Java's arm order makes the
        // later one unreachable under the earlier guard. examples/nondeterministic
        // overlaps by SEMANTICS instead — `coins >= 1` and `coins > 0` are two
        // separate non-terminating assignments in the same arm, so nothing orders
        // them and both really can fire. It must still warn.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/nondeterministic"));
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("nondeterminism")
                                && d.message().contains("coins")),
                "genuine semantic guard overlap must still be diagnosed");
    }

    @Test
    void aGuardedArmExcludesTheFallThroughInEveryFixture() {
        // The same fix, seen on a second, independently written corpus entry:
        //   case AckReceived(boolean addressInUse) when addressInUse -> new Init();
        //   case AckReceived ignored                                 -> new Bound();
        // The Requesting -> Bound edge used to be reported as unconditional, which
        // claimed a DHCPACK always commits the lease. It only does when the
        // duplicate-address check passed (RFC 2131 §4.4.1).
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/dhcp-client-claude"));
        StateMachine m = single(r);
        Transition commit = m.transitions().stream()
                .filter(t -> "Requesting".equals(t.from()) && "Bound".equals(t.to()))
                .findFirst().orElseThrow();
        assertNotNull(commit.guard(), "the fall-through arm is conditional, not unconditional");
        assertTrue(commit.guard().contains("addressInUse"), commit.guard());
    }

    @Test
    void aGuardSpelledOutsideABinaryOperatorIsStillRecovered() {
        // F12, part 1. Spoon 10.4.2 fills CtCase.getGuard() only when the guard is
        // a CtBinaryOperator; for a bare invocation (`when r.acceptable()`) or a
        // unary (`when !r.catastrophic()`) it leaves the slot null and PREPENDS the
        // guard expression into the arm body as a statement. Both halves of the
        // machine's behaviour then went wrong: the guard vanished from the label,
        // and the leaked expression was walked as if it produced a successor.
        //
        // The recovery keys on the ARROW case kind, which is exact rather than a
        // guess: JLS §14.11.1 gives an arrow arm a single expression, block or
        // throw, so two statements there is always the leak. See
        // examples/plumbing's colon-arm control for why the kind check is
        // load-bearing.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/lcp_automation"));
        StateMachine m = single(r);

        assertTrue(m.transitions().stream()
                        .anyMatch(t -> "Stopped".equals(t.from()) && "AckSent".equals(t.to())
                                && t.guard() != null && t.guard().contains("acceptable")),
                "an invocation-shaped `when` clause must reach the edge label");
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> "ReqSent".equals(t.from()) && "Stopped".equals(t.to())
                                && t.guard() != null && t.guard().contains("catastrophic")),
                "a unary-shaped `when` clause must reach the edge label");

        // And the leaked guard must not also be an edge: 13 LcpEvent records over
        // 10 states is 130 cells, +14 guard splits = 144 arms, of which 31 throw.
        assertEquals(113, m.transitions().size(), "exactly the 113 value-producing arms");
        assertEquals(0, m.unresolvedTransitionCount());
    }

    @Test
    void initialStateFallsBackToASeededLocalOnlyWhenUnanimous() {
        // A dense automaton defeats the structural rules exactly when it is most
        // faithful: in RFC 1661's LCP every state INCLUDING Initial has incoming
        // edges, so "no incoming, some outgoing" has no candidate, and the machine
        // class holds no state field either. The weakest rule — a hierarchy-typed
        // local seeded with `new Concrete()` — recovers it, and says so.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/lcp_automation"));
        StateMachine m = single(r);

        assertEquals("Initial", m.initialState().orElse(null));
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("inferred from")
                                && d.message().contains("weaker evidence")),
                "an inferred initial state must be reported as weaker evidence");
    }

    // ---- initial state: no rule may pick between rival candidates -----------

    /**
     * The seeded-FIELD rule is the strongest of the three and was the only one
     * that did not require its candidates to agree: it returned the first field
     * the model walk happened to reach. Two drivers over one hierarchy, seeded
     * from different states, therefore had the machine's start decided by
     * filename — on this fixture the old rule reported {@code Shut}, and
     * renaming {@code PrimaryDriver} so it sorted last reported {@code Flowing}
     * from byte-identical logic.
     *
     * <p>The relation in {@code examples/rivalseeds} is strongly connected, so
     * the structural rule below has no candidate either and cannot mask the
     * outcome: abstaining here means the machine reports a GAP, which is the
     * honest answer when the program does not say where it starts.
     */
    @Test
    void rivalSeededFieldsAbstainRatherThanLetSourceOrderDecide() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/rivalseeds"));
        StateMachine sluice = named(r, "Sluice");

        assertEquals(3, sluice.allStates().size());
        assertEquals(9, sluice.transitions().size(), "3 states x 3 commands");
        assertEquals(9, sluice.resolvedTransitionCount());

        assertTrue(sluice.initialState().isEmpty(),
                "rival seeds must yield a reported gap, not a coin flip: got "
                        + sluice.initialState().orElse(null));
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("disagree on the initial state")
                                && d.message().contains("Shut")
                                && d.message().contains("Flowing")),
                "the abstention must name both rival candidates, not pass silently");
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("initial state could not be determined")),
                "and the gap itself must still be reported");
    }

    /**
     * The negative control, in the same model. {@code Damper} also has two
     * drivers holding a seeded field — so the rule collects more than one
     * candidate — but they agree, and an agreed answer must still be reported.
     * Without this, "require unanimity" and "give up as soon as a second driver
     * exists" are indistinguishable, and the second silently costs an initial
     * state on every program that constructs its machine twice.
     *
     * <p>{@code Damper} is strongly connected too, so {@code Parked} cannot have
     * come from the structural rule: it is the seeded-field rule answering.
     */
    @Test
    void agreeingSeededFieldsStillYieldAnInitialState() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/rivalseeds"));
        StateMachine damper = named(r, "Damper");

        assertEquals("Parked", damper.initialState().orElse(null));
        assertFalse(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("rivalseeds.Damper")
                                && d.message().contains("disagree on the initial state")),
                "agreeing seeds are not a disagreement");
    }

    /**
     * The second control, and the one that keeps the unanimity rule from costing
     * more than it saves. A state's own flyweight instance — {@code static final
     * Ratchet INSTANCE = new Locked();} declared inside {@code Locked} — is
     * hierarchy-typed with a constructor-call default, so it reaches the
     * seeded-field rule looking exactly like a driver's current-state field.
     *
     * <p>{@code examples/rivalseeds.Ratchet} has ONE real driver, seeded FREE,
     * beside two such flyweights. Counting the flyweights as candidates makes
     * them disagree with the driver, at which point unanimity abstains and a
     * machine whose start state IS stated in its source reports a gap instead.
     * Ablating the exclusion does exactly that: {@code Free} is lost and the run
     * warns that {@code [Free, Locked]} disagree. Fixing a fabricated answer
     * must not manufacture a lost one.
     */
    @Test
    void flyweightSingletonsDoNotVetoARealDriversSeed() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/rivalseeds"));
        StateMachine ratchet = named(r, "Ratchet");

        assertEquals("Free", ratchet.initialState().orElse(null),
                "the driver's seed is the only seed; the flyweights are not candidates");
        assertFalse(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("rivalseeds.Ratchet")
                                && d.message().contains("disagree on the initial state")),
                "a state's own singleton must not be reported as a rival seed");
    }

    /**
     * Scope isolation for the seeded-field rule, which the fixture gets for free
     * by holding three hierarchies in one package: each machine's seeds must be
     * read only for that machine, or they would resolve each other's start
     * states. The rule filters on the field's declared TYPE for the same reason
     * the mutation path does.
     */
    @Test
    void seededFieldsDoNotLeakBetweenMachinesInOneModel() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/rivalseeds"));

        assertTrue(named(r, "Sluice").initialState().isEmpty(),
                "the other machines' agreeing seeds must not resolve Sluice");
        assertEquals("Parked", named(r, "Damper").initialState().orElse(null),
                "Sluice's disagreement must not suppress Damper");
        assertEquals("Free", named(r, "Ratchet").initialState().orElse(null),
                "Sluice's disagreement must not suppress Ratchet");
    }

    /**
     * The machine's own code does not say where the machine starts, and
     * {@code examples/valueforms} is the fixture that proves it: it has no
     * driver, no context object and no seeded local outside the hierarchy, so
     * the honest report is a GAP. Two separate rules were reading its internals
     * as evidence and each produced a different fabricated answer.
     *
     * <ul>
     *   <li>The seeded-FIELD rule saw {@code Armed.INSTANCE = new Armed()} and
     *       {@code Idle.INSTANCE = new Idle()} — flyweight singletons, both
     *       hierarchy-typed with a constructor-call default, and it reported
     *       {@code Armed} purely because that file was walked first. Unanimity
     *       alone would abstain here, so what the singleton exclusion buys on
     *       THIS fixture is the diagnostic: two flyweights are not two drivers
     *       disagreeing, and saying so would be a false statement about the
     *       program. Where it is load-bearing for the answer is
     *       {@code rivalseeds.Ratchet} — see
     *       {@link #flyweightSingletonsDoNotVetoARealDriversSeed()}.</li>
     *   <li>The seeded-LOCAL rule then saw {@code Signal next = new Firing();}
     *       inside {@code Armed.on} — a successor under construction, not a
     *       seed. So abstaining at rule 1 does not by itself fix this fixture:
     *       it moves the fabricated answer from {@code Armed} to {@code Firing}.
     *       The rule rests on the local being a DRIVER's starting point, which
     *       is a claim about code outside the machine.</li>
     * </ul>
     */
    @Test
    void theMachinesOwnInternalsAreNotInitialStateEvidence() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/valueforms"));
        StateMachine m = single(r);

        String got = m.initialState().orElse(null);
        assertNotEquals("Firing", got, "a successor built in a local inside the hierarchy is not a seed");
        assertTrue(m.initialState().isEmpty(),
                "with no driver anywhere, the honest report is a gap: got " + got);
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("initial state could not be determined")),
                "and the gap must be reported, not passed over in silence");
        assertFalse(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("disagree on the initial state")),
                "two flyweight singletons are not two drivers disagreeing");

        // The initial-state rules touch nothing else: enumeration stays exact and
        // the recovered relation is unchanged.
        assertEquals(6, m.allStates().size());
        assertEquals(10, m.transitions().size());
        assertEquals(9, m.resolvedTransitionCount());
    }

    @Test
    void theCentralizedFunctionCountIsOfDistinctHosts() {
        // The signature-based recognizer and DispatchCommitDetector legitimately
        // overlap — `Door transition(Door, Event)` whose body is `return switch` is
        // seen by both — and the reason text summed them. examples/door has exactly
        // ONE transition function and was reported as two. Extraction was never
        // affected (it deduped on declaringType#signature); this uses the same key
        // so the reported number and the walked set cannot drift apart.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/door"));
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("1 centralized transition function")),
                "door has one transition function, not two");
    }

    // ---- F14: a branch that throws does not fall through --------------------

    @Test
    void aThrowingRejectionBranchNegatesIntoTheFallThroughGuard() {
        // `if (bad) throw ...; yield new A();` has no `else`, yet the producer
        // below is reached only when the test failed. The fall-through guard was
        // already threaded across siblings, but "does this branch fall through?"
        // was answered from `return`/`yield` alone — so a branch leaving by a
        // THROW, which is how an arm rejects an input, was read as falling
        // through and the producer was reported as unconditional. Guards feed the
        // SCXML `cond` attribute and the nondeterminism analysis, so this was a
        // silent loss in a dimension the thesis reports on.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/throwguards"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of(CommitForm.VALUE_RETURN), m.commitForms());
        assertEquals(Set.of("Charged", "Venting", "Faulted", "Bleeding", "Latched", "Purging"),
                stateIds(m));
        assertEquals(6, m.transitions().size());
        assertEquals(0, m.unresolvedTransitionCount());

        // A bare `throw`.
        assertTrue(guardOf(m, "Charged", "Venting").contains("RESET"),
                "the producer below a throwing branch fires only when the test failed");
        // A block whose LAST statement is a throw — the bookkeeping call before
        // it does not change how the block completes.
        assertTrue(guardOf(m, "Venting", "Charged").contains("PURGE"),
                "a block ending in a throw completes abruptly too");
        // An exhaustive switch statement, every arm of which throws: no arm and
        // no `break` can carry control past it.
        assertTrue(guardOf(m, "Faulted", "Charged").contains("RESET"),
                "an exhaustive all-throwing switch cannot complete normally");

        // Every recovered guard here is a NEGATION of the rejection test, which
        // is the whole content of the finding.
        for (String from : List.of("Charged", "Venting", "Faulted")) {
            assertTrue(guardOf(m, from, null).startsWith("!("),
                    from + "'s guard must be the negated rejection test");
        }

        // NEGATIVE CONTROL 1 — the Bleeding arm CONTAINS a throw but takes it
        // only conditionally, so it completes normally and its producer is
        // unconditional. Reading "contains a throw" as "always throws" would
        // fabricate a guard: the edge would claim not to fire on OPEN, a
        // stronger claim than the source makes and the direction the soundness
        // invariant forbids.
        assertTrue(hasResolved(m, "Bleeding", "Faulted"));
        assertNull(guardOrNull(m, "Bleeding", "Faulted"),
                "a conditionally-thrown branch still falls through; its successor is unguarded");

        // NEGATIVE CONTROL 2 — the Latched arm's switch is exhaustive, but its
        // default arm leaves by `break`, so control resumes immediately after it.
        // That switch is a bare sibling rather than an `if` branch, so misjudging
        // it does not merely mislabel a guard: the producer below would be
        // written off as unreachable and the EDGE WOULD BE LOST.
        assertTrue(hasResolved(m, "Latched", "Venting"),
                "a switch left by `break` completes normally; the producer after it is live");
        assertNull(guardOrNull(m, "Latched", "Venting"),
                "and it is reached on every input, so it carries no guard");

        // NEGATIVE CONTROL 3 — the Purging arm's switch ends in a `default:`
        // label carrying no statements. An empty arm is answered by the group
        // below it, which is exactly why the LAST one cannot be: there is
        // nothing below, so control falls out of the switch. Answering "yes"
        // there deletes this edge and reports the machine as a clean 5/5 — a
        // transition dropped with no unresolved marker, which is the one
        // outcome the record-everything invariant forbids.
        assertTrue(hasResolved(m, "Purging", "Charged"),
                "an empty trailing arm falls out of the switch; the producer after it is live");
        assertNull(guardOrNull(m, "Purging", "Charged"));
    }

    /** The guard of the one resolved edge out of {@code from} (to {@code to}, or wherever). */
    private String guardOf(StateMachine m, String from, String to) {
        String g = guardOrNull(m, from, to);
        assertNotNull(g, "expected a guard on the edge out of " + from);
        return g;
    }

    private String guardOrNull(StateMachine m, String from, String to) {
        List<Transition> edges = m.transitions().stream()
                .filter(t -> t.from().equals(from) && (to == null || to.equals(t.to())))
                .toList();
        assertEquals(1, edges.size(), "expected exactly one edge out of " + from);
        String g = edges.get(0).guard();
        return g == null || g.isBlank() ? null : g;
    }

    // ---- negative controls -------------------------------------------------

    @Test
    void foreignCodomainFoldIsRejected() {
        // Acceptance #6 / precision guard B1. Every structural signal the widened
        // recognizer looks for is present — a sealed hierarchy, a driver holding a
        // field of that type, exhaustive switches over it in both accepted commit
        // positions — except the one that matters: nothing commits a Mode value.
        // The switches fold the hierarchy into String and int.
        //
        // Widening a recognizer is where false positives enter, and this is the
        // one that would have entered: an "automaton" whose every state has zero
        // transitions. The commit requirement is not a convenience check, it is
        // the whole discriminator.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/foreignfold"));
        assertTrue(r.isEmpty(), "an exhaustive fold into a foreign codomain is not a state machine");
        boolean explained = r.diagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("no transition producer found"));
        assertTrue(explained, "rejection reason should be reported");
    }

    @Test
    void shapeSumTypeIsRejected() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/shape"));
        assertTrue(r.isEmpty(), "a plain sum type must not be classified as an FSM");
        boolean explained = r.diagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("no transition producer found"));
        assertTrue(explained, "rejection reason should be reported");

        // The abstention must be worded as an abstention. Claiming "plain sum
        // type" asserts a classification the analysis never made: a sealed type
        // reaches this branch just as readily by being Σ, or by being dispatched
        // somewhere the recognizers cannot see.
        assertFalse(r.diagnostics().stream()
                        .anyMatch(d -> d.message().toLowerCase().contains("plain sum type")),
                "the rejection must not over-claim what the hierarchy is");
    }

    @Test
    void treeBuilderIsRejectedBySiblingNestedGuard() {
        // Precision guard for F8. `treebuilder.Expr` is shaped exactly like the
        // carrier encoding — one consistently-named method per permitted subtype,
        // returning a non-hierarchy carrier (`Rewrite`) that wraps Expr values —
        // yet it is a recursive tree rewrite, not an automaton. The difference is
        // positional: `new Add(l.result(), r.result())` NESTS hierarchy values
        // inside a bigger hierarchy node instead of producing a peer of the
        // current one.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/treebuilder"));
        assertTrue(r.isEmpty(), "a compositional sealed type must not be classified as an FSM");
        boolean explained = r.diagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("composed into one another"));
        assertTrue(explained, "rejection should name the compositional guard");
    }

    // ---- analysis scope isolation ------------------------------------------

    @Test
    void mutationMachinesDoNotAbsorbEachOthersAssignments() {
        // Regression: the mutation encoding identified state fields and mutators by
        // simple NAME across the whole model, because `ctx.setState(...)` returns
        // void and offers nothing typed to anchor on. Both PortalContext and
        // VendMachine call their field `state`, so analysing them together made
        // each machine swallow the other's assignments as edges with an
        // undetermined source — Portal acquired transitions guarded by `coins >= 1`.
        //
        // The pollution is one-directional (a foreign type can never resolve to a
        // state of this hierarchy) so it never invented a wrong *resolved* edge,
        // but it inflated the unresolved count and corrupted per-corpus recall.
        // Analysing the two together must now give exactly what analysing each
        // alone gives.
        StateMachine portalAlone = named(new Analyzer().analyze(modelOf("examples/gofcontext")), "Portal");
        StateMachine vendAlone = named(new Analyzer().analyze(modelOf("examples/nondeterministic")), "Vend");

        Launcher launcher = new Launcher();
        launcher.addInputResource("examples/gofcontext");
        launcher.addInputResource("examples/nondeterministic");
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        ExtractionResult together = new Analyzer().analyze(launcher.getModel());

        StateMachine portal = named(together, "Portal");
        StateMachine vend = named(together, "Vend");

        assertEquals(edgeSet(portalAlone), edgeSet(portal),
                "Portal's edges must not depend on what else is in the model");
        assertEquals(edgeSet(vendAlone), edgeSet(vend),
                "Vend's edges must not depend on what else is in the model");

        // The signature of the old bug: an edge with no attributable source.
        assertFalse(portal.transitions().stream().anyMatch(t -> "<unknown>".equals(t.from())),
                "no edge from another machine may leak in");
        assertFalse(vend.transitions().stream().anyMatch(t -> "<unknown>".equals(t.from())),
                "no edge from another machine may leak in");
        assertEquals(0, portal.unresolvedTransitionCount());
        assertEquals(0, vend.unresolvedTransitionCount());
    }

    private StateMachine named(ExtractionResult r, String name) {
        return r.machines().stream().filter(m -> m.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no machine named " + name));
    }

    private Set<String> edgeSet(StateMachine m) {
        return m.transitions().stream().map(Transition::toString).collect(Collectors.toSet());
    }

    // ---- combined ----------------------------------------------------------

    @Test
    void allExamplesTogether() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples"));
        List<String> names = r.machines().stream().map(StateMachine::name).toList();
        assertTrue(names.contains("TrafficLight"));
        assertTrue(names.contains("Door"));
        assertTrue(names.contains("TcpState"), "the carrier-encoded machine is recognised in the corpus");
        assertTrue(names.contains("Latch"), "the bare-field commit is recognised in the corpus");
        assertTrue(names.contains("Phase"), "the local-accumulator commit is recognised in the corpus");
        assertFalse(names.contains("Shape"), "Shape must be excluded");
        assertFalse(names.contains("Expr"), "the compositional tree builder must be excluded");
        assertFalse(names.contains("Mode"), "an exhaustive fold into a foreign codomain must be excluded");
        assertFalse(names.contains("Event"), "an event alphabet is not a state hierarchy");

        // No machine may be rooted at an event type. Checked on QUALIFIED names,
        // because `Signal` is both a machine (valueforms) and an alphabet
        // (barefield, accumulator, http2) in this corpus — a simple-name check
        // would be unable to tell the two apart and would pass vacuously.
        Set<String> roots = r.machines().stream()
                .map(StateMachine::qualifiedName).collect(Collectors.toSet());
        for (String eventType : List.of("examples.barefield.Signal", "examples.accumulator.Signal",
                "http2.Signal", "http2.StreamEvent", "http2stream.Event", "tcp.Event")) {
            assertFalse(roots.contains(eventType), eventType + " is Σ, not a state hierarchy");
        }

        // Stratification, on all three axes. The encoding axis has exactly two
        // positions and the corpus exercises both...
        Set<StateMachine.Encoding> encodings = r.machines().stream()
                .map(StateMachine::encoding).collect(Collectors.toSet());
        assertTrue(encodings.contains(StateMachine.Encoding.POLYMORPHIC));
        assertTrue(encodings.contains(StateMachine.Encoding.CENTRALIZED_DISPATCH));

        // ...while the orthogonal successor-form axis is what separates the
        // carrier-encoded machines from the plain ones.
        Set<SuccessorForm> forms = r.machines().stream()
                .flatMap(m -> m.successorForms().stream()).collect(Collectors.toSet());
        assertTrue(forms.contains(SuccessorForm.CONSTRUCTION));
        assertTrue(forms.contains(SuccessorForm.SELF));
        assertTrue(forms.contains(SuccessorForm.SINGLETON_FIELD));
        assertTrue(forms.contains(SuccessorForm.ENUM_CONSTANT));

        // ...and the commit axis is what separates the idioms that share an
        // encoding. All four positions are exercised, which is the whole point of
        // reporting it: recall stratified by idiom rather than pooled per encoding.
        Set<CommitForm> commits = r.machines().stream()
                .flatMap(m -> m.commitForms().stream()).collect(Collectors.toSet());
        assertEquals(Set.of(CommitForm.VALUE_RETURN, CommitForm.FIELD_MUTATION,
                        CommitForm.LOCAL_ACCUMULATOR, CommitForm.POLY_CARRIER), commits,
                "the corpus must exercise every commit form");
    }

    // ---- name collisions: two states sharing a simple name --------------------

    /**
     * A {@code permits} clause may legally name two types with the same simple
     * name, so the simple name is not an identity. Keyed on it, the two
     * {@code Idle} states of {@code examples/namecollision} become one node and
     * the two {@code UPGRADE} edges between them become the same
     * {@code (from, to, event, guard, resolved)} tuple — at which point the
     * extractor's transition set discards one and reports a clean 11/11. A
     * transition lost with no unresolved marker is the one failure mode the
     * record-everything invariant exists to prevent, so the count is the
     * assertion that matters here.
     */
    @Test
    void statesSharingASimpleNameStayDistinct() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/namecollision"));
        StateMachine m = single(r);

        // Enumeration is exact either way — the collision costs edges, not states.
        assertEquals(8, m.allStates().size(), "4 permitted subtypes + 4 enum constants");

        // Every state must be separately addressable; a duplicate id here is the
        // bug itself, and StateMachine reports it for exactly that reason.
        assertEquals(Set.of(), m.duplicateStateIds(), "no two states may share an id");
        assertEquals(8, stateIds(m).size(), "8 states must yield 8 distinct ids");

        // Uncollided states keep their bare simple name; only the collided ones
        // lengthen, and only as far as they must to become unique.
        assertTrue(stateIds(m).containsAll(
                        Set.of("namecollision.Idle", "Legacy.Idle", "Phase.IDLE", "Mode.IDLE",
                                "ACTIVE", "BULK", "Phase", "Mode")),
                "ids were " + stateIds(m));

        // The two edges the collision used to merge. Under simple-name ids both
        // read (Idle, Idle, UPGRADE) and one was silently dropped.
        assertEquals(12, m.transitions().size(), "12 arms, none merged away");
        assertEquals(12, m.resolvedTransitionCount());
        assertTrue(hasEventEdge(m, "namecollision.Idle", "UPGRADE", "Legacy.Idle"));
        assertTrue(hasEventEdge(m, "Legacy.Idle", "UPGRADE", "namecollision.Idle"));

        // ...and the same one level down, where two permitted enums both declare
        // IDLE. These targets are reached from different sources, so they survived
        // deduplication even before the fix — but both pointed at the same node.
        assertTrue(hasEventEdge(m, "namecollision.Idle", "RESET", "Mode.IDLE"));
        assertTrue(hasEventEdge(m, "Legacy.Idle", "OPEN", "Phase.IDLE"));

        // The initial-state rules must speak the same ids as the states, or the
        // machine would declare an initial state that no state matches.
        assertEquals("namecollision.Idle", m.initialState().orElse(null));
        assertTrue(m.allStates().stream()
                        .filter(State::isInitial)
                        .allMatch(st -> st.id().equals("namecollision.Idle")),
                "exactly the disambiguated state may be flagged initial");
    }

    /**
     * Every endpoint an edge names must be a state that exists. This is what a
     * disambiguation applied only to state ids — and not to the transition
     * endpoints produced alongside them — would break: the states would separate
     * correctly while every edge went on naming the old ambiguous spelling, which
     * DOT would silently render as a phantom node.
     */
    @Test
    void everyEdgeEndpointNamesADeclaredState() {
        for (String fixture : List.of("examples/namecollision", "examples/valueforms",
                "examples/lcp_automation", "examples/tcp")) {
            ExtractionResult r = new Analyzer().analyze(modelOf(fixture));
            for (StateMachine m : r.machines()) {
                Set<String> ids = stateIds(m);
                for (Transition t : m.transitions()) {
                    // Pseudo-states are deliberately not states; everything else is.
                    if (!t.from().startsWith("<")) {
                        assertTrue(ids.contains(t.from()),
                                fixture + ": edge source '" + t.from() + "' is not a state");
                    }
                    if (t.isResolved() && t.to() != null && !t.to().startsWith("<")) {
                        assertTrue(ids.contains(t.to()),
                                fixture + ": edge target '" + t.to() + "' is not a state");
                    }
                }
            }
        }
    }
}
