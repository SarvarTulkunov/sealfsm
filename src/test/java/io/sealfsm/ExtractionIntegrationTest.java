package io.sealfsm;

import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.SuccessorForm;
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

        assertEquals(StateMachine.Encoding.CENTRALIZED, m.encoding());
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

        assertEquals(StateMachine.Encoding.CENTRALIZED, m.encoding());
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
        // Dispatch lives in a per-state method, so this is DISTRIBUTED dispatch.
        // The carrier is a property of the successor *form*, not of the encoding —
        // the two axes are reported separately.
        assertEquals(StateMachine.Encoding.DISTRIBUTED, m.encoding());

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

    // ---- negative controls -------------------------------------------------

    @Test
    void shapeSumTypeIsRejected() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/shape"));
        assertTrue(r.isEmpty(), "a plain sum type must not be classified as an FSM");
        boolean explained = r.diagnostics().stream()
                .anyMatch(d -> d.message().toLowerCase().contains("no hierarchy-returning or carrier-based"));
        assertTrue(explained, "rejection reason should be reported");
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
        assertFalse(names.contains("Shape"), "Shape must be excluded");
        assertFalse(names.contains("Expr"), "the compositional tree builder must be excluded");
        assertFalse(names.contains("Event"), "an event alphabet is not a state hierarchy");

        // Stratification, on both axes. The encoding axis has exactly two
        // positions and the corpus exercises both...
        Set<StateMachine.Encoding> encodings = r.machines().stream()
                .map(StateMachine::encoding).collect(Collectors.toSet());
        assertTrue(encodings.contains(StateMachine.Encoding.DISTRIBUTED));
        assertTrue(encodings.contains(StateMachine.Encoding.CENTRALIZED));

        // ...while the orthogonal successor-form axis is what separates the
        // carrier-encoded machines from the plain ones.
        Set<SuccessorForm> forms = r.machines().stream()
                .flatMap(m -> m.successorForms().stream()).collect(Collectors.toSet());
        assertTrue(forms.contains(SuccessorForm.CONSTRUCTION));
        assertTrue(forms.contains(SuccessorForm.SELF));
        assertTrue(forms.contains(SuccessorForm.SINGLETON_FIELD));
        assertTrue(forms.contains(SuccessorForm.ENUM_CONSTANT));
    }
}
