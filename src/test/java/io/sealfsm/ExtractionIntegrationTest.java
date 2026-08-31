package io.sealfsm;

import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.model.CommitForm;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.SuccessorForm;
import io.sealfsm.model.Transition;
import org.junit.jupiter.api.Test;
import spoon.Launcher;
import spoon.reflect.CtModel;
import spoon.reflect.declaration.CtType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // ---- 3a: (CENTRALIZED_SWITCH, CARRIER_RETURN) ---------------------------

    /**
     * The cell that was unreachable while dispatch and commit were fused: a
     * centralized transition table whose arms hand the successor to a wrapper.
     *
     * <p>Three switches over one hierarchy on one class, differing only in what
     * they fold into, so the codomain is the only thing the recognizer can be
     * reacting to. Exactly one is a machine, and the two controls — a fold into a
     * record with no hierarchy-typed slot, and one into a record with two — must
     * contribute nothing. Accepting either would report an exhaustive fold as an
     * automaton, or resolve a successor by field order.
     */
    @Test
    void centralizedSwitchReturningACarrierIsExtracted() {
        ExtractionResult r = new Analyzer().analyze(modelOf("src/test/resources/carrierdispatch"));
        StateMachine m = r.machines().stream().filter(x -> x.name().equals("Signal"))
                .findFirst().orElseThrow();
        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertTrue(m.commitForms().contains(CommitForm.CARRIER_RETURN),
                "the successor is installed through a wrapper, at a centralized locus");
        assertEquals(3, m.allStates().size());
        assertEquals(3, m.transitions().size(),
                "one edge per arm of the ONE committing switch; the two controls add none");
        assertEquals(3, m.resolvedTransitionCount());
        assertEquals(3, m.resolvedTransitionCount(), "every successor is unwrapped from the carrier");
        assertTrue(hasResolved(m, "Idle", "Live"));
        assertTrue(hasResolved(m, "Live", "Done"));
        assertTrue(hasResolved(m, "Done", "Done"));

        // The same commit at the other centralized locus, in the same package: an
        // instanceof chain. Held beside the switch so no difference of file or
        // context can stand in for the spelling of the discrimination.
        StateMachine chain = r.machines().stream().filter(x -> x.name().equals("Latch"))
                .findFirst().orElseThrow();
        assertTrue(chain.commitForms().contains(CommitForm.CARRIER_RETURN));
        assertTrue(hasResolved(chain, "Open", "Shut"));
        assertTrue(hasResolved(chain, "Shut", "Open"));
    }

    // ---- 4a: F9 at the carrier commit, at a CENTRALIZED locus ---------------

    /**
     * A helper that always throws is not a producer — asked at the locus 3a
     * opened, not only on the bare-H path where the rule started.
     *
     * <p>{@code Undefined.illegal(b, event)} is syntactically INDISTINGUISHABLE
     * from a carrier factory: a call whose own type is outside the hierarchy,
     * carrying a hierarchy-typed argument. Nothing in the expression separates
     * them; only the callee's body does, and JLS §8.4.7 makes "no {@code return}
     * anywhere in a non-void method" a proof rather than a guess. Its argument is
     * the current state, which is how such a helper is nearly always called, so
     * the fabrication would be a SELF-LOOP — one per specification-undefined cell,
     * and a real transition table has many.
     *
     * <p>The negative control is the sharp half, and it is why the rule may not be
     * "contains a throw": {@code recover} throws on one path and returns on
     * another, so it CAN produce a successor and its edge must survive — with the
     * rejection branch's negated test as its guard, which is F14 doing its job at
     * the same site.
     */
    @Test
    void anAlwaysThrowingCarrierHelperContributesNoEdgeWhileAReturningOneKeepsIts() {
        ExtractionResult r = new Analyzer().analyze(modelOf("src/test/resources/carrierreject"));
        StateMachine m = single(r);
        assertEquals(3, m.allStates().size());
        assertTrue(m.commitForms().contains(CommitForm.CARRIER_RETURN));

        assertTrue(hasResolved(m, "Ready", "Busy"), "an ordinary carrier arm resolves");
        assertTrue(hasResolved(m, "Spent", "Ready"),
                "the helper that CAN return keeps its edge — the rule is a proof, not "
                        + "'this method mentions throw'");
        assertTrue(m.transitions().stream().noneMatch(t -> "Busy".equals(t.from())),
                "the always-throwing arm is an undefined input: no edge at all, not even "
                        + "an unresolved one, and above all not a fabricated self-loop");
        assertEquals(2, m.transitions().size());
        assertEquals(2, m.resolvedTransitionCount());
    }

    // ---- F24: rule 4 may not fire on a folded callee's parameter -------------

    /**
     * A root-typed parameter inside a FOLDED callee is not provably the current
     * state, and must not resolve as a self-loop.
     *
     * <p>Rule 4 in {@code TransitionResolver} reads "a root-typed selector means
     * stay in the matched state", and its premise is that the variable IS the
     * discriminated value. At a dispatch that is established. Inside a folded
     * callee it is not, because nothing had ever mapped the call's arguments onto
     * the callee's parameters — so a parameter holding a fallback, a default, or
     * any other state the caller chose to pass was stamped "proven self-loop".
     *
     * <p>{@code examples/lcp_automation_chatgpt} is the two-root-typed-parameter
     * fixture this codebase was missing, and it is emphatic: <b>109 of its 111
     * edges</b> were fabricated self-loops, including {@code Initial --UP-->
     * Initial} where RFC 1661 §4.1 says {@code Closed}. Every state looked
     * isolated in the rendered diagram, because almost nothing connected them.
     *
     * <p>The two survivors are the real edges — the cells whose successor is
     * written as a construction — and everything else is now an explicit
     * unresolved edge. 2/111 is a poor recall figure and an honest one; 111/111
     * was neither.
     */
    @Test
    void aFoldedCalleesParameterIsNotAssumedToBeTheCurrentState() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/lcp_automation_chatgpt"));
        StateMachine m = single(r);
        assertEquals(10, m.allStates().size(), "state enumeration is exact regardless");
        assertEquals(111, m.transitions().size(), "every cell is still RECORDED");
        assertEquals(2, m.resolvedTransitionCount(),
                "only the two constructed successors are proven; the rest are gaps");

        assertTrue(hasEventEdge(m, "ReqSent", "TO_MINUS", "Stopped"));
        assertTrue(hasEventEdge(m, "AckSent", "TO_MINUS", "Stopped"));

        // The sharp assertion: no state may claim a RESOLVED self-loop here. Each
        // one would be an edge the analysis cannot prove, and RFC 1661 contradicts
        // them outright.
        assertTrue(m.transitions().stream()
                        .noneMatch(t -> t.isResolved() && t.from().equals(t.to())),
                "a fabricated self-loop is the one failure mode the invariant forbids");
    }

    /**
     * The control that keeps the fix from being a blanket suppression: the SAME
     * RFC, recovered through the same inter-procedural fold, must be unaffected.
     * Its folded helpers receive the matched state, so reading it back out is a
     * genuine self-loop and rule 4 still applies.
     */
    @Test
    void theSameRfcRecoveredThroughValueReturnIsUnaffected() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/lcp_automation"));
        StateMachine m = single(r);
        assertEquals(10, m.allStates().size());
        assertEquals(113, m.transitions().size());
        assertEquals(113, m.resolvedTransitionCount(),
                "the parameter-mapping restriction costs this machine nothing");
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> t.isResolved() && t.from().equals(t.to())),
                "its stay-put cells are real self-loops and must survive");
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

    /**
     * The transition relation as comparable text, so two extractions of the same
     * machine can be asserted equal rather than compared edge by edge.
     */
    private Set<String> relationOf(StateMachine m) {
        return m.transitions().stream()
                .map(t -> t.from() + " -" + t.event() + "[" + t.guard() + "]-> "
                        + (t.isResolved() ? t.to() : "?"))
                .collect(Collectors.toSet());
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

    // ---- F18: the summarisability boundary of the inter-procedural fold -----

    @Test
    void interproceduralFoldReadsReturnsHiddenInEveryModelledConstruct() {
        // F18. The fold used to run a second, strictly weaker walker of its own
        // (`collectReturns`) that descended blocks and `if`s and nothing else, so
        // a helper whose returns sat inside a `switch`, a loop or a `try` was not
        // summarisable — and `private H fromIdle(E e) { switch (e) { case START:
        // return ...; } }` is the ordinary way a per-event table is factored.
        //
        // The encoding is held fixed across the four helpers and only the
        // construct enclosing the return varies, so anything that differs between
        // them is the walker's reach and nothing else.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/hiddenreturns"));
        StateMachine m = single(r);

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of(CommitForm.VALUE_RETURN), m.commitForms());
        assertEquals(Set.of("Idle", "Priming", "Running", "Halted"), stateIds(m));
        assertEquals("Idle", m.initialState().orElse(null));

        // switch STATEMENT: three returns, three edges, each labelled with the Σ
        // symbol its arm matched. The labels are the proof that the fold reuses
        // the real walker — a bespoke return-collector has no notion of a case
        // label and would have had to invent one.
        assertTrue(hasEventEdge(m, "Idle", "START", "Priming"));
        assertTrue(hasEventEdge(m, "Idle", "STOP", "Halted"));
        assertTrue(hasResolved(m, "Idle", "Idle"), "the default arm is a self-loop");

        // try / catch: the catch is the classic error transition and must arrive
        // under the exceptional guard, not as an unconditional edge.
        assertTrue(hasResolved(m, "Priming", "Running"));
        assertTrue(hasResolved(m, "Priming", "Priming"));
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> t.isResolved() && "Priming".equals(t.from())
                                && "Halted".equals(t.to())
                                && t.guard() != null && t.guard().contains("exception")),
                "the catch-block producer is reached, and under the exceptional guard");

        // THE SOUNDNESS CASE. `fromRunning` has one return inside a loop and one
        // after it. The old test for summarisability was `returns.isEmpty()`,
        // which only detects a summary that failed ENTIRELY; this one succeeded
        // partially, so the trailing `new Running()` was folded and published as a
        // resolved self-loop while the `Halted` target was DROPPED — a real
        // transition gone with no unresolved marker, behind a clean-looking n/n.
        // That is the one outcome the record-everything invariant forbids.
        assertTrue(hasResolved(m, "Running", "Running"));
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> t.isResolved() && "Running".equals(t.from())
                                && "Halted".equals(t.to())
                                && t.guard() != null && t.guard().contains("FAULT")),
                "a return inside a loop is a real successor, not one to be dropped");

        // NEGATIVE CONTROL and standing probe: `fromHalted` returns from inside a
        // `synchronized` block, which the walker does not descend. It must be
        // recorded as unresolved, and reported. The test is what the walk ACTUALLY
        // reached, not whether the body matched a list of constructs someone
        // remembered to extend; teach the walker about `synchronized` and this
        // fails loudly rather than quietly ceasing to test anything.
        assertEquals(1, m.unresolvedTransitionCount());
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> "Halted".equals(t.from()) && !t.isResolved()),
                "a return the walk cannot reach stays an unresolved edge");
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("could not be reached by the walk")),
                "an unread return is reported, not silently folded away");

        assertEquals(9, m.transitions().size());
    }

    // ---- F9: a helper that cannot return normally is not a producer ---------

    @Test
    void nonReturningHelperYieldsNoEdgeButAnUnreadableReturnStaysUnresolved() {
        // F9 and its negative control in one hierarchy. Every producer returns the
        // hierarchy type and every one of them yields an EMPTY return-summary to a
        // walker that stops at the first construct it does not model, so they are
        // indistinguishable by the summary alone and only the REASON the summary
        // is empty separates them.
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
        assertEquals(6, m.transitions().size(),
                "a helper that cannot return normally is not a transition producer");

        // F18 — `escalate` returns from inside a `switch`. That used to be the
        // negative control (an unreadable target) and is now the positive case:
        // the fold runs the ordinary walker over a callee body, so the arm
        // resolves. It is ALSO the context-sensitivity control. `escalate`
        // re-switches on the state, and its `default:` arm cannot run at the only
        // call site, which has already matched `Idle`. Fold it without that
        // reasoning and a second edge appears sourced at `<unknown>` — an origin
        // invented out of a context the walk was holding all along.
        assertTrue(hasEventEdge(m, "Idle", "ESCALATE", "Fired"),
                "a return inside a switch is readable and must be read");
        assertTrue(m.transitions().stream()
                        .noneMatch(t -> "<unknown>".equals(t.from())),
                "the caller's from-state is known, so no folded edge may invent an origin");

        // NEGATIVE CONTROL, and the whole risk of F9: `defer` DOES return a state,
        // but from inside a `synchronized` block the walker does not descend. Its
        // summary is empty exactly as `reject`'s is, so a rule keyed on that
        // emptiness would drop a real target. It must stay UNRESOLVED — recorded,
        // never guessed, never dropped.
        assertEquals(2, m.unresolvedTransitionCount(),
                "a return the walk cannot reach is unresolved, not absent");
        assertTrue(m.transitions().stream()
                        .anyMatch(t -> "Idle".equals(t.from()) && "DEFER".equals(t.event())
                                && !t.isResolved()),
                "the unreadable-return helper must remain an unresolved edge from Idle");
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().contains("could not be reached by the walk")),
                "a partly-read body is reported, so the gap is auditable against the source");

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

    // ---- F21: F9 on the carrier path ---------------------------------------

    @Test
    void nonReturningCarrierHelperFabricatesNoEdge() {
        // F21. `Undefined.illegal(new Snagged(), order)` and
        // `Haul.to(new Snagged())` are the SAME expression shape: a static call
        // whose own type is outside the hierarchy, carrying a hierarchy-typed
        // argument. The carrier walk unwraps one level and reads that argument as
        // the successor, and nothing in the expression tells the two apart — only
        // the callee's body does.
        //
        // F9 is the instrument that reads the body, but it was reachable only
        // through `resolveInterprocedural`, which returns early in carrier mode.
        // That early return is right about folding — the carrier encoding is
        // strictly intra-procedural, so a successor COMPUTED inside a helper stays
        // unresolved rather than being guessed. It is wrong as a place to hide F9,
        // which computes nothing: it observes that there is no successor, from a
        // fact the compiler already checked (JLS §8.4.7). So the carrier path had
        // no F9 at all, and every undefined cell became a RESOLVED edge — the one
        // failure mode the soundness invariant forbids outright. Ablate the hook
        // and this fixture reports 8/8 for a machine with five transitions.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/throwcarrier"));
        StateMachine capstan = named(r, "Capstan");

        assertEquals(StateMachine.Encoding.POLYMORPHIC, capstan.encoding());
        assertEquals(Set.of(CommitForm.POLY_CARRIER), capstan.commitForms());
        assertEquals(Set.of("Slack", "Taut", "Snagged"), stateIds(capstan));
        assertEquals("Slack", capstan.initialState().orElse(null));

        // The four real edges, plus the `demand` control below.
        assertTrue(hasEventEdge(capstan, "Slack", "CRANK", "Taut"));
        assertTrue(hasEventEdge(capstan, "Slack", "SNAG", "Snagged"));
        assertTrue(hasEventEdge(capstan, "Taut", "PAY_OUT", "Slack"));

        // THE FINDING, spelling one: `illegal(this, order)` is how an undefined
        // cell is usually written, so the fabrication is usually a self-loop —
        // roughly forty of them on a specification the size of RFC 1661's LCP,
        // every one reported resolved.
        assertFalse(capstan.transitions().stream()
                        .anyMatch(t -> "Slack".equals(t.from()) && "Slack".equals(t.to())),
                "an always-throwing helper carrying `this` is not a self-loop");

        // THE FINDING, spelling two: the fabricated target is a DIFFERENT state,
        // so the rule being tested is about the callee, not about `this`.
        assertFalse(capstan.transitions().stream()
                        .anyMatch(t -> "Taut".equals(t.from()) && "Snagged".equals(t.to())),
                "an always-throwing helper carrying a sibling state is not an edge");

        // NEGATIVE CONTROL, in the same method as spelling two so that no
        // difference of file or context can stand in for the body: `Haul.stay(this)`
        // has the identical call shape and DOES return, so its self-loop survives.
        assertTrue(capstan.transitions().stream()
                        .anyMatch(t -> t.isResolved() && "Taut".equals(t.from())
                                && "Taut".equals(t.to())),
                "a returning carrier of the same shape keeps its self-loop");

        // NEGATIVE CONTROL for exactness, and the sharpest one: `demand` throws on
        // one path and returns on another, so it CAN return. The rule is "no
        // `return` anywhere", which JLS §8.4.7 makes a proof; "contains a `throw`"
        // is a guess, and acting on it would delete this real edge with no
        // unresolved marker.
        assertTrue(hasEventEdge(capstan, "Snagged", "CLEAR", "Snagged"),
                "a conditionally-throwing helper still returns and keeps its edge");

        assertEquals(5, capstan.transitions().size(),
                "the relation the source actually defines");
        assertEquals(0, capstan.unresolvedTransitionCount(),
                "a suppressed cell is an undefined input, not an unresolved target");

        // Suppression is counted and reported, so it is reclassified rather than
        // silent — the same treatment F9 already gives it centrally.
        assertTrue(r.diagnostics().stream()
                        .anyMatch(d -> d.message().startsWith("3 call(s) to a helper")
                                && d.message().contains("cannot return normally")),
                "the three undefined cells are reported, not merely absent");
    }

    @Test
    void aShadowBodiedCarrierKeepsEveryEdge() {
        // F11's negative control, on the carrier path, and load-bearing in a way
        // it is not centrally. The fold only ever asks F9 about an in-model helper
        // returning the hierarchy type; a CARRIER is any wrapper at all, so a
        // library one is an ordinary input rather than an exotic case. `Hoist`
        // wraps every successor in `Optional.of(...)`, for which Spoon supplies a
        // reflective SHADOW: a real signature with an empty `{ }` body. That body
        // has no `return` because it was never parsed, so its emptiness carries no
        // information — and read as proof it deletes the entire machine.
        //
        // Ablate the shadow check and this reports 0/0: three states, five edges
        // gone, no unresolved marker anywhere. It also corrupts the commit axis to
        // FIELD_MUTATION, because the F2 fallback runs once the carrier path finds
        // nothing — so the failure would show up as a machine filed under the
        // wrong row of the stratified table as well as an empty one.
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/throwcarrier"));
        StateMachine hoist = named(r, "Hoist");

        assertEquals(StateMachine.Encoding.POLYMORPHIC, hoist.encoding());
        assertEquals(Set.of(CommitForm.POLY_CARRIER), hoist.commitForms(),
                "the JDK carrier is still a carrier commit, not a mutation");
        assertEquals(Set.of("Parked", "Raising", "Held"), stateIds(hoist));
        assertEquals("Parked", hoist.initialState().orElse(null));

        assertTrue(hasEventEdge(hoist, "Parked", "RAISE", "Raising"));
        assertTrue(hasEventEdge(hoist, "Raising", "HOLD", "Held"));
        assertTrue(hasResolved(hoist, "Held", "Parked"));
        assertEquals(5, hoist.transitions().size(),
                "every edge through an unread body survives");
        assertEquals(0, hoist.unresolvedTransitionCount());

        // Nothing in this hierarchy throws, so no suppression may be attributed to
        // it. A count here would be a false claim wearing the diagnostic that
        // exists to make F9 auditable.
        assertTrue(r.diagnostics().stream()
                        .noneMatch(d -> d.message().contains("cannot return normally")
                                && d.message().contains("Hoist")),
                "an unread body is never reported as a proven rejection");
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
        // encoding. Every position is exercised, which is the whole point of
        // reporting it: recall stratified by idiom rather than pooled per encoding.
        //
        // CARRIER_RETURN joined the set when the dispatch and commit axes were
        // separated. It is the carrier commit at a CENTRALIZED locus, which no
        // recognizer could reach while each hard-coded a locus and a commit as one
        // pair, and examples/lcp_automation_chatgpt is the corpus member that was
        // rejected outright ("no transition producer found") until it existed.
        Set<CommitForm> commits = r.machines().stream()
                .flatMap(m -> m.commitForms().stream()).collect(Collectors.toSet());
        // MUTATOR_ARGUMENT joined it in the same refactor. It was previously pooled
        // into FIELD_MUTATION, which reported an observation and an inference under
        // one label: a field write is a commit the analysis SEES, while a mutator
        // argument additionally rests on a structural reading of the callee's body.
        // Pooling them made a gap in the inference unattributable, which is the
        // exact failure the stratified table exists to prevent.
        assertEquals(Set.of(CommitForm.VALUE_RETURN, CommitForm.FIELD_MUTATION,
                        CommitForm.LOCAL_ACCUMULATOR, CommitForm.POLY_CARRIER,
                        CommitForm.CARRIER_RETURN, CommitForm.MUTATOR_ARGUMENT), commits,
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

    // ---- nested roots ------------------------------------------------------

    /**
     * A sealed machine declared INSIDE a hierarchy that is not one must still be
     * found. {@code SealedHierarchyDetector} withholds {@code Body} from the root
     * list because {@code Message} claims it as a composite state, and that claim
     * lapses the moment {@code Message} is rejected — so the child has to be
     * re-offered as a root in its own right. Before the fix the only thing
     * reported about this file was that {@code Message} had been skipped.
     */
    @Test
    void nestedMachineSurvivesItsParentBeingRejected() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/nestedroots"));
        StateMachine m = single(r);

        assertEquals("Body", m.name());
        assertEquals("nestedroots.Body", m.qualifiedName());
        assertEquals(Set.of("Empty", "Streaming", "Complete"), stateIds(m));
        assertTrue(hasResolved(m, "Empty", "Streaming"));
        assertTrue(hasResolved(m, "Streaming", "Complete"));
        assertTrue(hasResolved(m, "Complete", "Empty"));
        assertEquals(0, m.unresolvedTransitionCount());
        assertEquals("Empty", m.initialState().orElse(null));

        // The parent stays rejected, and says what it released.
        assertTrue(r.diagnostics().stream().anyMatch(d ->
                        d.where().equals("nestedroots.Message")
                                && d.message().contains("nestedroots.Body")),
                "the rejected parent must name the hierarchy it re-offered");
    }

    /**
     * Re-offering must not manufacture a machine, and must give the child its own
     * diagnostic. {@code Envelope} abstains and its nested {@code Contents} is a
     * plain sum type; before the fix the only line printed named {@code Envelope},
     * so a reader could not tell whether {@code Contents} had been examined and
     * rejected or never looked at at all.
     */
    @Test
    void aReofferedHierarchyThatIsNotAMachineGetsItsOwnDiagnostic() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/nestedroots"));

        assertTrue(r.machines().stream().noneMatch(m -> m.name().equals("Contents")),
                "Contents is a plain sum type; re-offering must not accept it");
        assertTrue(r.diagnostics().stream().anyMatch(d ->
                        d.where().equals("nestedroots.Contents")
                                && d.message().contains("skipped")),
                "the re-offered child must be named in a diagnostic of its own");
    }

    /**
     * NEGATIVE CONTROL, and the load-bearing one: the compositional veto must not
     * be escapable by re-offering. The veto is judged against the hierarchy set of
     * whichever root is classified, and a child's set is strictly narrower — in
     * {@code new Wrap(child)} the argument is typed {@code Node}, which is in
     * {@code Node}'s hierarchy and not in {@code Branch}'s, so the same expression
     * reads as composition for the parent and as a peer production for the child.
     * Classified on its own {@code Branch} is accepted as POLYMORPHIC (asserted
     * below, so the control cannot quietly stop testing anything), which is exactly
     * the tree-builder false positive the veto exists to prevent.
     */
    @Test
    void aVetoedHierarchyDoesNotReleaseItsNestedRoots() {
        CtModel model = modelOf("examples/nestedroots");
        ExtractionResult r = new Analyzer().analyze(model);

        assertTrue(r.machines().stream().noneMatch(m -> m.name().equals("Branch")),
                "a member of a recursive data type is still a recursive data type");
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.where().equals("nestedroots.Branch")),
                "a vetoed root must not re-offer anything, so Branch is never classified");

        // The control bites only if Branch WOULD be accepted on its own.
        CtType<?> branch = model.getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals("nestedroots.Branch"))
                .findFirst().orElseThrow();
        StateMachineClassifier.Classification onItsOwn =
                new StateMachineClassifier().classify(branch, model);
        assertTrue(onItsOwn.isStateMachine(),
                "if Branch were rejected anyway this control would assert nothing");
        assertEquals(StateMachine.Encoding.POLYMORPHIC, onItsOwn.encoding());
    }

    // ---- F17: instanceof-chain dispatch in a named method -------------------

    /**
     * The shape the finding is about: a field selector, no hierarchy-typed
     * parameter, no switch. Every recognizer was blind to it, so the whole
     * hierarchy was reported as "no transition producer found" — a false negative
     * on what is the dominant dispatch idiom in Java written before pattern
     * matching.
     */
    @Test
    void instanceofChainOverAFieldIsAMachine() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/chaindispatch"));
        StateMachine m = named(r, "Relay");

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of("Idle", "Live", "Tripped"), stateIds(m));
        assertTrue(m.commitForms().contains(CommitForm.FIELD_MUTATION));

        assertTrue(hasResolved(m, "Idle", "Live"), "Idle -> Live");
        assertTrue(hasResolved(m, "Live", "Idle"), "Live -> Idle");
        assertTrue(hasResolved(m, "Live", "Tripped"), "Live -> Tripped");
        assertTrue(hasResolved(m, "Tripped", "Idle"), "Tripped -> Idle");
        assertEquals(0, m.unresolvedTransitionCount());
        assertEquals("Idle", m.initialState().orElse(null));
    }

    /**
     * The type test is the arm LABEL, not a guard. Recording it as a guard is how
     * the second half of the finding presented: the hierarchy-in / hierarchy-out
     * host was recognised all along, and every one of its edges still came out
     * with an undetermined source and an unresolvable target.
     */
    @Test
    void aTypeTestSelectsTheFromStateRatherThanGuardingTheEdge() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/chaindispatch"));
        StateMachine m = named(r, "Shutter");

        assertEquals(0, m.unresolvedTransitionCount());
        assertTrue(m.transitions().stream().noneMatch(
                        t -> t.from().equals("<unknown>") || t.from().equals("<entry>")),
                "no edge may be left with an undetermined source");
        assertTrue(m.transitions().stream().noneMatch(
                        t -> t.guard() != null && t.guard().contains("instanceof")),
                "the type test is consumed as the from-state, never re-reported as a guard");

        // A conjoined event test still splits into a Σ label.
        assertTrue(hasEventEdge(m, "Open", "LOWER", "Closing"), "Open --LOWER--> Closing");
        assertTrue(m.alphabet().containsAll(Set.of("LOWER", "SEAL", "RAISE")));
    }

    /**
     * Because every link returns, {@code return current;} below the chain is
     * reached exactly in the states no link claimed — the same closed-world
     * reasoning the permits clause licenses. The {@code Open} link carries an
     * extra condition, so {@code Open} is still reachable there and keeps its edge
     * <em>under the negation</em>; {@code Closing}, tested with nothing else, is
     * genuinely gone. Getting either direction wrong is silent: one deletes a real
     * edge, the other reports a conditional edge as unconditional.
     */
    @Test
    void aClosedChainLeavesTheUntestedStatesToTheStatementBelowIt() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/chaindispatch"));
        StateMachine m = named(r, "Shutter");

        assertTrue(hasResolved(m, "Shut", "Shut"), "Shut was never tested: it reaches the tail");
        Transition openLoop = m.transitions().stream()
                .filter(t -> t.from().equals("Open") && "Open".equals(t.to()))
                .findFirst().orElseThrow(() ->
                        new AssertionError("Open stays Open unless the command is LOWER"));
        assertNotNull(openLoop.guard(), "that edge is conditional and must say so");
        assertTrue(openLoop.guard().contains("LOWER"));
        assertEquals(5, m.transitions().size());
    }

    /**
     * NEGATIVE CONTROL, codomain: the same discrimination folding into a
     * {@code String} field is not a machine. It sits in the same package as a
     * field-mutation machine that IS one, so what separates them is the declared
     * type of the field written and nothing else.
     */
    @Test
    void anInstanceofFoldIntoAForeignCodomainIsNotAMachine() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/chaindispatch"));
        assertTrue(r.machines().stream().noneMatch(m -> m.name().equals("Glyph")));
    }

    /**
     * NEGATIVE CONTROL, composition: a rewrite that nests one hierarchy value
     * inside another is a recursive data type. Unlike {@code examples/treebuilder}
     * this hierarchy declares no methods, so neither the compositional veto nor
     * the distributed recognizer can be what rejects it.
     */
    @Test
    void anInstanceofTreeRewriteIsNotAMachine() {
        CtModel model = modelOf("examples/chaindispatch");
        ExtractionResult r = new Analyzer().analyze(model);
        assertTrue(r.machines().stream().noneMatch(m -> m.name().equals("Tree")));

        // The control bites only if nothing ELSE was already rejecting it.
        CtType<?> tree = model.getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals("chaindispatch.Tree"))
                .findFirst().orElseThrow();
        assertFalse(io.sealfsm.detect.CarrierTransitionDetector.composesItself(tree),
                "if the compositional veto fired, this would not be testing the chain guard");
        assertEquals(List.of(),
                StateMachineClassifier.findDistributedTransitionMethods(tree),
                "if the distributed recognizer had claimed it, the rejection would prove nothing");
    }

    /**
     * Two chains in one model must produce exactly the edges they produce alone.
     * The field-mutation path is the fragile one — {@code RelayBoard} and
     * {@code GlyphNamer} and {@code TreeFolder} all write a field in an
     * instanceof branch — so the commit test has to key on the declared TYPE.
     */
    @Test
    void chainMachinesDoNotAbsorbEachOthersAssignments() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/chaindispatch"));
        assertEquals(2, r.machines().size());
        for (StateMachine m : r.machines()) {
            for (Transition t : m.transitions()) {
                assertTrue(stateIds(m).contains(t.from()),
                        m.name() + " sourced an edge at " + t.from() + ", which is not its state");
            }
        }
    }

    // ---- F19: the stateful driver (H out, no H in) --------------------------

    /**
     * The recall half. {@code SashDriver.step(Nudge)} is a transition function that
     * never says so in its signature: the state lives in a field, so there is no
     * hierarchy-typed parameter for the centralized recognizer, the method is not
     * declared on the hierarchy so the distributed one skips it, and its dispatch is
     * a {@code switch} STATEMENT whose arms return — a commit the switch's parent
     * cannot show, so {@code DispatchCommitDetector} finds nothing either. The whole
     * hierarchy was reported as "no transition producer found".
     */
    @Test
    void aStatefulDriverDispatchingWithASwitchStatementIsAMachine() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/statefuldriver"));
        StateMachine m = named(r, "Sash");

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of("Seated", "Parted", "Raised"), stateIds(m));
        assertEquals(Set.of(CommitForm.VALUE_RETURN), m.commitForms());

        assertTrue(hasEventEdge(m, "Seated", "LIFT", "Parted"), "Seated --LIFT--> Parted");
        assertTrue(hasEventEdge(m, "Parted", "LIFT", "Raised"), "Parted --LIFT--> Raised");
        assertTrue(hasEventEdge(m, "Raised", "JAR", "Seated"), "Raised --JAR--> Seated");
        assertTrue(hasResolved(m, "Seated", "Seated"), "the default arm folds back");
        assertTrue(hasResolved(m, "Parted", "Seated"));
        assertTrue(hasResolved(m, "Raised", "Parted"));

        assertEquals(6, m.transitions().size());
        assertEquals(0, m.unresolvedTransitionCount());
        assertEquals("Seated", m.initialState().orElse(null));
        // Σ is still enumerated from the event parameter of a host with no state
        // parameter — the labels above are that enumeration, not method names.
        assertTrue(m.alphabet().containsAll(Set.of("LIFT", "DROP", "JAR")));
    }

    /**
     * The soundness half, and the one that does not announce itself: this machine
     * was never lost, it was reported wrong. F3 keeps an inter-procedural helper
     * from also being walked standalone by collecting the callees of every
     * recognised transition function — and the driver was not one, so its per-state
     * helpers were not recognised as its helpers. Each was then extracted on its own
     * with no from-state, adding one fabricated unresolved edge per input symbol on
     * top of the nine real ones: a complete, fully resolved relation published as
     * 9/12, with three edges out of a source that is not a state.
     */
    @Test
    void aStatefulDriversHelpersAreNotAlsoWalkedStandalone() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/statefuldriver"));
        StateMachine m = named(r, "Pump");

        assertEquals(Set.of("Idle", "Priming", "Running"), stateIds(m));
        assertEquals(9, m.transitions().size(), "3 states x 3 inputs, and nothing else");
        assertEquals(0, m.unresolvedTransitionCount());
        for (Transition t : m.transitions()) {
            assertTrue(stateIds(m).contains(t.from()),
                    "edge sourced at " + t.from() + ", which is not a state of this machine");
        }
        assertEquals("Idle", m.initialState().orElse(null));
    }

    /**
     * The negative control for the <em>routing</em> half. Recognising the host is
     * only half a change — something then has to walk it, and the two available
     * walks disagree about how much of it to read. A host handed the state computes
     * a successor end to end, so its whole body is the transition relation; a host
     * that only returns H read the state from a field, and its body is plumbing
     * around a dispatch. Walking this one whole reports the trailing
     * {@code return state;} as a successor with no source AND relabels the machine
     * VALUE_RETURN, putting an edge under the wrong row of the stratified table.
     */
    @Test
    void aFieldMutatingStatefulDriverIsWalkedAtItsDispatchNotWhole() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/statefuldriver"));
        StateMachine m = named(r, "Hatch");

        assertEquals(Set.of(CommitForm.FIELD_MUTATION), m.commitForms(),
                "the detector established the commit; the walk must not overwrite it");
        assertEquals(Set.of("Dogged", "Cracked", "Gaping"), stateIds(m));
        assertEquals(6, m.transitions().size());
        assertEquals(0, m.unresolvedTransitionCount());
        assertTrue(hasResolved(m, "Dogged", "Cracked"));
        assertTrue(hasResolved(m, "Cracked", "Gaping"));
        assertTrue(hasResolved(m, "Gaping", "Dogged"));
    }

    /**
     * The same control at corpus scale, and the reason the routing rule is not a
     * special case for one fixture: {@code examples/barefield} and
     * {@code examples/http2-stream-gemini} are both stateful drivers this recognizer
     * now sees for the first time, and both must be unchanged by that.
     */
    @Test
    void existingFieldMutationMachinesAreUnaffectedByTheWidenedRecognizer() {
        StateMachine latch = single(new Analyzer().analyze(modelOf("examples/barefield")));
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), latch.commitForms());
        assertEquals(4, latch.transitions().size());
        assertEquals(0, latch.unresolvedTransitionCount());

        StateMachine http2 = single(new Analyzer().analyze(modelOf("examples/http2-stream-gemini")));
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), http2.commitForms());
        assertEquals(20, http2.transitions().size());
        assertEquals(0, http2.unresolvedTransitionCount());
    }

    /**
     * The withholding is only lifted by an ABSTENTION, so an accepted parent keeps
     * its nested sealed hierarchies as composite states — the existing behaviour,
     * pinned here because the worklist is what could break it.
     */
    @Test
    void anAcceptedParentKeepsItsNestedHierarchiesAsStates() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/valueforms"));
        StateMachine m = single(r);
        assertTrue(m.topLevelStates().stream().anyMatch(s -> s.id().equals("Phase") && s.isComposite()),
                "the permitted enum stays a composite state of Signal");
        assertTrue(r.machines().stream().noneMatch(sm -> sm.name().equals("Phase")));
    }

    // ---- F20: the compositional veto is bounded ----------------------------

    /**
     * The shape the finding is about. {@code Retrying} carries the {@code Attempt}
     * it succeeded — the ordinary way a retry or backoff protocol is written, and
     * exactly the {@code record Retrying(LcpState previous, int attempts)} an RFC
     * automaton declares. Every producer therefore writes
     * {@code new Retrying(this, ...)}, a hierarchy value in a constructor argument
     * list, and the unbounded veto read one such expression as proof that the whole
     * type was a tree. Four states, ten edges and a complete relation were deleted
     * on it, under a diagnostic asserting "recursive data type".
     *
     * <p>What separates the two is checkable and is what the fix keys on:
     * structural recursion DESCENDS into the value it matched and rebuilds a node
     * out of its parts, whereas this wraps the value WHOLE. A fold never does the
     * latter — a node containing itself is not a smaller problem.
     */
    @Test
    void aStateCarryingItsPredecessorIsStillAStateMachine() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/retrystate"));
        StateMachine m = named(r, "Attempt");

        assertEquals(StateMachine.Encoding.POLYMORPHIC, m.encoding());
        assertEquals(Set.of("Ready", "Retrying", "Exhausted", "Done"), stateIds(m));
        assertEquals(10, m.transitions().size());
        assertEquals(0, m.unresolvedTransitionCount(),
                "a predecessor pointer resolves like any other construction");

        assertTrue(hasResolved(m, "Ready", "Retrying"), "new Retrying(this, 1)");
        assertTrue(hasResolved(m, "Retrying", "Retrying"), "new Retrying(this, attempts + 1)");
        assertTrue(hasResolved(m, "Retrying", "Exhausted"), "new Exhausted(this)");
        assertTrue(hasResolved(m, "Retrying", "Done"));
        assertTrue(hasResolved(m, "Exhausted", "Ready"));
        assertEquals("Ready", m.initialState().orElse(null));
    }

    /**
     * The same finding on the centralized path, where the current state is spelled
     * as the arm's type-pattern binding rather than {@code this}. It carries the
     * receiver half too: {@code new Waiting(w, w.misses() + 1)} passes an
     * {@code int} computed off the current state, and a flat scan of that argument's
     * subtree saw the RECEIVER {@code w} and called the whole argument a hierarchy
     * value — so the most ordinary thing a retry state does, counting, read as
     * composition.
     *
     * <p>{@code PollDriver.handle} returns {@code void}, so this dispatch is the
     * hierarchy's only producer: rejecting it does not mislabel an edge, it loses
     * the machine. At HEAD this fixture reported "no transition producer found".
     */
    @Test
    void aCentralizedDispatchCarryingItsMatchedBindingIsStillAMachine() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/retrystate"));
        StateMachine m = named(r, "Poll");

        assertEquals(StateMachine.Encoding.CENTRALIZED_DISPATCH, m.encoding());
        assertEquals(Set.of(CommitForm.FIELD_MUTATION), m.commitForms());
        assertEquals(Set.of("Fresh", "Waiting", "Stale"), stateIds(m));
        assertEquals(7, m.transitions().size());
        assertEquals(0, m.unresolvedTransitionCount());

        assertTrue(hasResolved(m, "Fresh", "Waiting"), "new Waiting(f, 1)");
        assertTrue(hasEventEdge(m, "Waiting", "MISS", "Waiting"), "new Waiting(w, w.misses() + 1)");
        assertTrue(hasEventEdge(m, "Waiting", "EXPIRE", "Stale"), "new Stale(w)");
        assertEquals("Fresh", m.initialState().orElse(null));
    }

    /**
     * NEGATIVE CONTROL for the threshold, and the load-bearing one: bounding the
     * veto by a count alone opens a hole exactly where recursive sealed types are
     * commonest — the two-member tree ({@code permits Base, Stack}) whose single
     * recursive member rebuilds itself. One production, one member, so
     * "two across two" clears it; {@code flatten()} returns the hierarchy type on
     * both members so the distributed recognizer accepts it; and a tree is published
     * as an automaton whose one edge is {@code Stack -> Stack}.
     *
     * <p>The self-composition disjunct is what closes it, and it is not an extra
     * heuristic: descending into the current state's PARTS and reassembling a node
     * around them is the definition of a fold.
     */
    @Test
    void aSingleMemberTreeIsStillVetoed() {
        CtModel model = modelOf("examples/retrystate");
        ExtractionResult r = new Analyzer().analyze(model);

        assertTrue(r.machines().stream().noneMatch(m -> m.name().equals("Layer")),
                "a recursive data type with one recursive member is still a recursive data type");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.where().equals("retrystate.Layer")
                        && d.message().toLowerCase().contains("composed into one another")),
                "the rejection must name the compositional guard");

        // The control bites only if something WOULD otherwise accept it.
        CtType<?> layer = model.getAllTypes().stream()
                .filter(t -> t.getQualifiedName().equals("retrystate.Layer"))
                .findFirst().orElseThrow();
        assertFalse(StateMachineClassifier.findDistributedTransitionMethods(layer).isEmpty(),
                "if no recognizer claimed Layer, this control would assert nothing");
    }

    /**
     * NEGATIVE CONTROL for the downgrade: what happens to the one nested production
     * the bounded veto now lets through. {@code Plain.wrap(Frame other) -> new
     * Boxed(other)} nests a hierarchy value that is neither the current state nor a
     * part of it, so it is evidence about one expression and not about the type.
     *
     * <p>Two things must both hold, and they pull in opposite directions. The
     * hierarchy is NOT deleted — that was the unbounded veto, and the other two
     * members' edges must still resolve. And the composing edge is NOT published as
     * a resolved transition: a composed node's relationship to the current state is
     * containment, and succession was never established. It is recorded as
     * unresolved, with the reason in its note and a diagnostic counting it — the
     * record-everything invariant applied to precisely the case the bound admits.
     */
    @Test
    void aLoneNestedProductionIsDowngradedPerEdgeNotVetoed() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/retrystate"));
        StateMachine m = named(r, "Frame");

        assertEquals(Set.of("Plain", "Boxed", "Torn"), stateIds(m));
        assertEquals(3, m.transitions().size());
        assertEquals(1, m.unresolvedTransitionCount(), "exactly the composing edge");
        assertTrue(hasResolved(m, "Boxed", "Torn"), "the other members are unaffected");
        assertTrue(hasResolved(m, "Torn", "Torn"));

        Transition composed = m.transitions().stream()
                .filter(t -> !t.isResolved()).findFirst().orElseThrow();
        assertEquals("Plain", composed.from(),
                "the source state is known; it is the TARGET that is not a successor");
        assertNotNull(composed.note());
        assertTrue(composed.note().contains("composes a hierarchy value"),
                "the note must say why, not merely that it is unresolved: " + composed.note());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.where().equals("retrystate.Frame")
                        && d.message().contains("NEST a hierarchy value")),
                "a downgraded production is counted, not silently absorbed");
    }

    /**
     * The corpus-scale control: bounding a precision guard may only ADD machines.
     * The three fixtures the veto exists for are rejected by three different code
     * paths — {@code treebuilder} by {@code composesItself} (and again by the
     * switch form, via {@code CentralRewriter}), {@code nestedroots.Node} by the
     * two-members threshold, {@code chaindispatch.Tree} by the chain form — so a
     * bound applied in one place and not the others would show up here.
     */
    @Test
    void boundingTheVetoDoesNotAcceptAnyKnownRecursiveDataType() {
        assertTrue(new Analyzer().analyze(modelOf("examples/treebuilder")).isEmpty(),
                "treebuilder: two self-composing productions, on the carrier path");

        ExtractionResult nested = new Analyzer().analyze(modelOf("examples/nestedroots"));
        assertTrue(nested.machines().stream().noneMatch(m -> m.name().equals("Node")));
        assertTrue(nested.machines().stream().noneMatch(m -> m.name().equals("Branch")),
                "a vetoed root must still release nothing");

        ExtractionResult chain = new Analyzer().analyze(modelOf("examples/chaindispatch"));
        assertTrue(chain.machines().stream().noneMatch(m -> m.name().equals("Tree")),
                "the instanceof spelling of the same rewrite");
        assertEquals(2, chain.machines().size(), "and the two real machines are untouched");
    }

    // ---- F22: a name corroborates, it never recognises ------------------------

    /**
     * F22 — a state mutator is a SHAPE, and a conventional name alone recognises
     * nothing. The three methods of {@code BoltRig} have the same signature (one
     * {@code Bolt} in, nothing out), so only their bodies can tell them apart.
     *
     * <p>{@code assume} commits its parameter and is named nothing in particular:
     * finding it is the positive statement that discovery is by shape.
     * {@code engage} commits the same parameter laundered through a null check, and
     * is the negative control for the narrowing — losing it would be SILENT, since
     * F10 ignores an expression statement no commit form claims, so there would be
     * no unresolved marker to notice. {@code become} is the trap: a conventional
     * mutator name on an audit hook that commits nothing. The old rule admitted a
     * method whose name was one of six English words regardless of its body, and an
     * admitted method has its call sites' ARGUMENT published as the successor — so
     * {@code rig.become(i)} became a RESOLVED self-loop on {@code Idle}, a
     * fabricated resolved edge and, with it, a nondeterminism warning about a
     * machine that is deterministic.
     */
    @Test
    void aStateMutatorIsRecognisedByShapeAndNotByItsName() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/mutatorshape"));
        StateMachine m = named(r, "Bolt");

        assertEquals(Set.of("Idle", "Live", "Spent"), stateIds(m));
        assertEquals("Idle", m.initialState().orElse(null));
        assertEquals(3, m.transitions().size(), "one edge per state, and nothing else");
        assertEquals(0, m.unresolvedTransitionCount());

        assertTrue(hasResolved(m, "Idle", "Live"), "a mutator called `assume` is still a mutator");
        assertTrue(hasResolved(m, "Live", "Spent"));
        assertTrue(hasResolved(m, "Spent", "Idle"), "a laundered commit is still a commit");

        assertTrue(m.transitions().stream()
                        .noneMatch(t -> "Idle".equals(t.from()) && "Idle".equals(t.to())),
                "an audit hook named like a setter contributes NO edge — not a resolved "
                        + "self-loop, and not an unresolved one either: nothing here is a "
                        + "transition whose target went unrecovered");
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.where().equals("mutatorshape.Bolt")
                        && d.message().contains("nondeterminism")),
                "the fabricated self-loop also invented an overlap on Idle");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.where().equals("mutatorshape.Bolt")
                        && d.message().contains("recognised by SHAPE alone")
                        && d.message().contains("assume") && d.message().contains("engage")),
                "the commit channel is reported, with the spelling as corroboration");
    }

    /**
     * The other half of F22, and the half a plain "one hierarchy-typed parameter
     * whose body writes a hierarchy-typed field" rule misses. {@code VentRig.restart}
     * passes exactly that test — and its parameter is the state being LEFT, read to
     * be audited, while what lands in the field is chosen by the callee. Reading a
     * call's argument as the successor is licensed only when the mutator commits
     * what it was handed.
     *
     * <p>Held beside a real mutator on the same class, so this asserts a NARROWING
     * and not an absence: the attributable edge survives, and the commit that cannot
     * be attributed is RECORDED without a source rather than replaced by a fiction.
     * At HEAD this hierarchy reports a clean-looking 2/2 in which
     * {@code Venting -> Venting} is fabricated and the real {@code Venting -> Sealed}
     * is missing — a wrong answer wearing a perfect score.
     */
    @Test
    void aMutatorMustCommitWhatItWasHanded() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/mutatorshape"));
        StateMachine m = named(r, "Vent");

        assertEquals(Set.of("Sealed", "Venting"), stateIds(m));
        assertTrue(hasResolved(m, "Sealed", "Venting"), "the real mutator's arm is unaffected");
        assertTrue(m.transitions().stream()
                        .noneMatch(t -> t.isResolved() && "Venting".equals(t.from())),
                "restart's argument is not its successor, so its call site claims nothing");
        assertEquals(1, m.unresolvedTransitionCount(),
                "restart's own commit is still recovered — sourceless, hence unresolved");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.where().equals("mutatorshape.Vent")
                        && d.message().contains("[restart]")),
                "the exclusion is a recall gap and must be reported, not silent");
    }

    /**
     * F22, the labelling half — a transition method's NAME is an input symbol only
     * when it DISCRIMINATES. This was a hard-coded list of English words held to be
     * "neutral", which is a claim about vocabulary rather than about the program,
     * and it failed on the corpus in the direction that fabricates: neither
     * {@code on} nor {@code wrap} was on the list, so every edge of
     * {@code retrystate}'s two machines was labelled with the transition function's
     * own name while the real input sat in the guard beside it.
     *
     * <p>{@code examples/cancellation} is the control that keeps the rule from
     * becoming "never label anything": its two callables are supplied by
     * {@code add} and {@code subscribe}, so there the name really is the input.
     * {@code examples/traffic} pins the answer the deleted list happened to get
     * right, which must not change.
     */
    @Test
    void aTransitionMethodNameIsAnEventSymbolOnlyWhenItDiscriminates() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/retrystate"));
        for (String name : List.of("Attempt", "Frame")) {
            StateMachine m = named(r, name);
            assertTrue(m.transitions().stream().allMatch(t -> t.event() == null),
                    name + ": one transition-method name across the hierarchy names the "
                            + "FUNCTION and discriminates nothing, so it is not a Σ symbol");
        }
        assertTrue(named(r, "Attempt").transitions().stream()
                        .anyMatch(t -> t.guard() != null && t.guard().contains("Signal.START")),
                "and the real input is still recovered, as a guard");
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.where().equals("retrystate.Attempt")
                        && d.message().contains("discriminates nothing")),
                "the un-recovered alphabet is a gap, and is reported as one");

        StateMachine cancel = single(new Analyzer().analyze(modelOf("examples/cancellation")));
        Set<String> events = cancel.transitions().stream()
                .map(Transition::event).filter(e -> e != null).collect(Collectors.toSet());
        assertEquals(Set.of("add", "subscribe"), events,
                "two suppliers: the name IS the input, and must survive");

        StateMachine traffic = single(new Analyzer().analyze(modelOf("examples/traffic")));
        assertTrue(traffic.transitions().stream().allMatch(t -> t.event() == null),
                "the one answer the word list got right must not change");
    }

    // ---- noClasspath type resolution ------------------------------------------
    //
    // Models are built with setNoClasspath(true), so a type Spoon cannot bind
    // still reaches the analysis as a reference carrying a guessed qualified
    // name. Every recognizer decides membership with
    // hierarchy.contains(ref.getQualifiedName()) against a set built from
    // RESOLVED declarations, so such a reference always answers "not in the
    // hierarchy" — exactly what a genuinely foreign type answers. Nothing
    // downstream could tell the two apart, so a result thinned by unreadable
    // input was reported as a finding about the program.
    //
    // These three hold the fixture fixed and vary only WHAT THE ANALYSIS WAS
    // ALLOWED TO READ, which is the condition itself rather than an imitation of
    // it: no fixture can encode an unresolvable type, because every example is
    // compiled by javac as part of being a fixture.

    private CtModel modelOfFiles(String... paths) {
        Launcher launcher = new Launcher();
        for (String p : paths) launcher.addInputResource(p);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    /**
     * Every {@code .java} file in {@code dir} except the named ones — the source
     * set with a hole in it.
     *
     * <p>Derived from the directory rather than listed by hand, and this is not
     * tidiness. A hard-coded list of the files to KEEP goes quietly stale: add a
     * file to the fixture and the partial model silently stops containing it, so
     * the test still passes while testing a different input than it says. Listing
     * what is WITHHELD states the condition directly, and the existence check
     * below makes the withholding itself falsifiable — rename the withheld file
     * and the test fails loudly instead of passing while withholding nothing.
     */
    private CtModel modelOfDirExcept(String dir, String... withheld) {
        for (String name : withheld) {
            assertTrue(Files.exists(Path.of(dir, name)),
                    "withheld file " + name + " is not in " + dir + " — this test would "
                            + "otherwise pass while withholding nothing");
        }
        Set<String> excluded = Set.of(withheld);
        try (var entries = Files.list(Path.of(dir))) {
            List<String> kept = entries
                    .filter(f -> f.getFileName().toString().endsWith(".java"))
                    .filter(f -> !excluded.contains(f.getFileName().toString()))
                    .map(Path::toString)
                    .sorted()
                    .toList();
            return modelOfFiles(kept.toArray(new String[0]));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private boolean mentionsResolution(ExtractionResult r) {
        return r.diagnostics().stream().anyMatch(d -> d.message().contains("did not resolve"));
    }

    @Test
    void anUnresolvedPermittedSubtypeIsReportedRatherThanSilentlyUnderReported() {
        ExtractionResult whole = new Analyzer().analyze(modelOf("examples/traffic"));
        StateMachine full = single(whole);
        assertEquals(3, full.allStates().size());
        assertEquals(3, full.resolvedTransitionCount());
        // Negative control: a hierarchy the analysis could read entirely says
        // nothing about resolution. Without this the test would pass on a tool
        // that simply warned about everything.
        assertFalse(mentionsResolution(whole),
                "a fully readable hierarchy must not report a resolution failure");

        ExtractionResult partial =
                new Analyzer().analyze(modelOfDirExcept("examples/traffic", "Yellow.java"));
        StateMachine thin = single(partial);

        // The state survives, and so now does its MEMBERSHIP. StateExtractor reads
        // the permits clause, which is a set of references; the clause is also the
        // compiler-checked statement that Yellow belongs to the hierarchy, and only
        // the SPELLING Spoon gives the reference is a guess. So the edge that
        // mentions the state comes back — this is the half the recovery buys.
        assertEquals(3, thin.allStates().size(),
                "the permits clause still enumerates the state by name");
        assertTrue(hasResolved(thin, "Green", "Yellow"),
                "an edge to a state the permits clause names must be recovered: that "
                        + "clause is the same source the exact state set already rests on");

        // ...and the half it does NOT buy must stay visible. Yellow's own producer
        // is in the file that was not read, so `Yellow -> Red` is unrecoverable
        // under this encoding. Reported as 2/2 that is a perfect score on a machine
        // missing a third of its relation — a dropped transition behind a clean
        // n/n with no unresolved marker, the one outcome the record-everything
        // invariant forbids by name. 2/3 is what the input actually supports, and
        // this assertion is why the recovery and the gap record are one change.
        assertEquals(3, thin.transitions().size());
        assertEquals(2, thin.resolvedTransitionCount());
        assertTrue(thin.resolvedTransitionCount() < full.resolvedTransitionCount(),
                "withholding a state's file must still cost the edges it produced");
        assertTrue(thin.transitions().stream()
                        .anyMatch(t -> !t.isResolved() && t.from().equals("Yellow")),
                "a state whose declaration was never read, and which no dispatch "
                        + "examined, must carry an explicit unresolved outgoing edge");
        assertFalse(thin.allStates().stream()
                        .anyMatch(s -> s.id().equals("Yellow") && s.isTerminal()),
                "an unread state is not absorbing — nothing was read about it");

        // Recovery may reconnect an edge to a state the permits clause enumerated.
        // It may never widen the state set nor point an endpoint outside it: a
        // resolved edge to a state the analysis never established is a fabrication,
        // not a recovery, and this is the assertion that would catch a future
        // "improvement" that promoted an unresolved reference on a simple-name
        // match instead of on the permits spelling.
        Set<String> ids = stateIds(thin);
        for (Transition t : thin.transitions()) {
            assertTrue(ids.contains(t.from()), "edge sourced outside the state set: " + t);
            assertTrue(!t.isResolved() || ids.contains(t.to()),
                    "edge resolved to a target outside the state set: " + t);
        }

        // The whole point: what was recovered and what was not must BOTH be
        // attributable, or the reader cannot tell an unreadable input from a
        // finding about the program.
        assertTrue(partial.diagnostics().stream().anyMatch(d ->
                        d.severity() == ExtractionResult.Severity.WARN
                                && d.message().contains("permitted subtype(s)")
                                && d.message().contains("examples.traffic.Yellow")),
                "the unread declaration must be named");
        assertTrue(partial.diagnostics().stream().anyMatch(d ->
                        d.severity() == ExtractionResult.Severity.WARN
                                && d.message().contains("outgoing transitions are unknown")
                                && d.message().contains("examples.traffic.Yellow")),
                "the gap the recovery cannot close must be reported, not merely counted");

        // PROVENANCE. A recovered edge and an edge resolved against a declaration
        // count the same in `2/3`, and a reader of a diagram or a recall table
        // cannot tell them apart from the score. So the weaker evidence is carried
        // in the model: the state is flagged, and the edges touching it are
        // countable. Both edges here touch Yellow.
        assertEquals(Set.of("Yellow"), thin.statesWithUnreadDeclaration());
        assertEquals(2, thin.transitionsViaUnreadDeclaration());
        assertTrue(full.statesWithUnreadDeclaration().isEmpty(),
                "the fully readable model must claim no degraded provenance at all");
        assertEquals(0, full.transitionsViaUnreadDeclaration());
    }

    @Test
    void aCentralizedDispatchSurvivesTheLossOfItsStateFiles() {
        // The same input error as the test above, under the other encoding — and
        // the case that makes the recovery worth having. A centralized dispatch
        // whose state files are absent reported "could not determine source state
        // for a switch case" once per arm, a 0/5 score, and six nondeterminism
        // warnings about a state called <unknown>: the switch-arm handling blamed
        // for what is an input problem. In a thesis reporting recall stratified by
        // idiom that is a misattributed recall figure, not merely a confusing
        // message.
        //
        // Under this encoding the withheld files are pure DATA — the whole
        // transition relation lives in DoorMachine, which WAS read — so the honest
        // answer is not a smaller relation with better wording. It is the same
        // relation.
        ExtractionResult partial = new Analyzer().analyze(modelOfDirExcept(
                "examples/door", "Open.java", "Closed.java", "Locked.java"));
        StateMachine thin = single(partial);
        ExtractionResult wholeDoor = new Analyzer().analyze(modelOf("examples/door"));
        StateMachine full = single(wholeDoor);

        assertEquals(relationOf(full), relationOf(thin),
                "the withheld files hold no transition logic, so withholding them "
                        + "must cost no edge");
        assertEquals(5, thin.transitions().size());
        assertEquals(5, thin.resolvedTransitionCount());

        assertTrue(partial.diagnostics().stream()
                        .map(ExtractionResult.Diagnostic::message)
                        .noneMatch(msg -> msg.contains("could not determine source state")),
                "every arm names its state once the pattern types are members again, "
                        + "so no arm may still be reported as unrecognised");
        Set<String> ids = stateIds(thin);
        assertEquals(Set.of("Open", "Closed", "Locked"), ids);
        assertTrue(thin.transitions().stream().allMatch(t -> ids.contains(t.from())),
                "no edge may be sourced at <unknown> or <entry> once the arms resolve");

        // Negative control for the gap record, and the mirror of the failure it
        // exists to prevent. Every state here WAS examined by a dispatch the
        // analysis read, so no unresolved outgoing edge may be manufactured for it:
        // one marker per unread state regardless of what examined it would report a
        // fully recovered machine as 5/8, understating a complete answer exactly as
        // reporting traffic 2/2 would overstate an incomplete one.
        assertEquals(0, thin.unresolvedTransitionCount());

        // Recovery changes what is LOST, never whether the reader is told. The
        // input error is still reported, and the states it touched are named.
        assertTrue(mentionsResolution(partial));
        assertTrue(partial.diagnostics().stream().anyMatch(d ->
                        d.message().contains("their successors, so their outgoing edges "
                                + "are recovered")
                                && d.message().contains("examples.door.Open")),
                "a state recovered only because a readable dispatch computes its "
                        + "successors must say so — its own declaration was still not read");

        // The initial state comes back too: DoorContext seeds `new Closed()`, and
        // that seed is only a candidate once Closed is a member again. Master
        // cannot answer this at all.
        assertEquals("Closed", thin.initialState().orElse(null));

        // PROVENANCE, and the reason a 5/5 here is not the same claim as the 5/5
        // beside it. Every one of these five edges was matched through a name
        // Spoon guessed for a declaration nobody read; the score cannot show that,
        // so the model carries it and the CLI prints it under the table.
        assertEquals(Set.of("Open", "Closed", "Locked"), thin.statesWithUnreadDeclaration());
        assertEquals(5, thin.transitionsViaUnreadDeclaration());
        assertTrue(full.statesWithUnreadDeclaration().isEmpty());
        assertEquals(0, full.transitionsViaUnreadDeclaration(),
                "the same relation read from complete source claims full provenance, "
                        + "which is the difference the flag exists to record");

        // Negative control: with every file present the identical switch produces
        // the full relation and no resolution diagnostic at all, so the messages
        // above track resolution and not merely the presence of a pattern arm.
        assertEquals(5, full.resolvedTransitionCount());
        assertFalse(mentionsResolution(wholeDoor));
    }

    @Test
    void anUnreadNestedMemberIsRecoveredUnderSpoonsOwnSpelling() {
        // The third shape the recovery meets, and the one where the SPELLING is
        // the whole question. `Legacy.Idle` is a nested type, which Spoon names
        // `namecollision.Legacy$Idle`; a rule that split the qualified name on `.`
        // alone would read that tail as the simple name and match nothing. The
        // state set already canonicalises `$` (StateNaming), and membership must
        // agree with it or the two halves of the tool disagree about the same
        // state again — this time about a state whose id was deliberately
        // lengthened to keep it distinct from `namecollision.Idle`.
        ExtractionResult whole = new Analyzer().analyze(modelOf("examples/namecollision"));
        StateMachine full = single(whole);
        ExtractionResult partial = new Analyzer().analyze(
                modelOfDirExcept("examples/namecollision", "Legacy.java"));
        StateMachine thin = single(partial);

        assertEquals(8, thin.allStates().size());
        assertTrue(stateIds(thin).contains("Legacy.Idle"),
                "the disambiguated id must survive the loss of the declaration, or the "
                        + "two Idle states collapse and one edge is silently discarded");
        assertEquals(relationOf(full), relationOf(thin),
                "the transition logic is entirely in LinkMachine, which was read");
        assertEquals(12, thin.resolvedTransitionCount());
        assertEquals(0, thin.unresolvedTransitionCount());
        assertTrue(partial.diagnostics().stream().anyMatch(d ->
                        d.severity() == ExtractionResult.Severity.WARN
                                && d.message().contains("permitted subtype(s)")
                                && d.message().contains("Legacy$Idle")),
                "the unread declaration must still be named");

        // Provenance is keyed on the disambiguated id, so it survives the collision
        // that made this fixture exist: 5 of the 12 edges touch `Legacy.Idle`, and
        // the other 7 are as strong as they were.
        assertEquals(Set.of("Legacy.Idle"), thin.statesWithUnreadDeclaration());
        assertEquals(5, thin.transitionsViaUnreadDeclaration());

        // STANDING PROBE, not an assertion about this machine's edges. One
        // withheld type reaches this model under several guessed spellings at once
        // — the permits clause's `namecollision.Legacy$Idle` and a bare
        // `Legacy.Idle` from elsewhere — which is exactly why recovery admits only
        // the spelling the permits clause wrote and is therefore partial by
        // construction: a use site Spoon spelled differently stays unresolved. If a
        // Spoon bump ever makes the guesses agree, this fails loudly and the claim
        // in hierarchyQualifiedNames' javadoc needs re-measuring rather than
        // re-asserting.
        assertTrue(partial.diagnostics().stream().anyMatch(d ->
                        d.message().contains("did not resolve under noClasspath")
                                && d.message().contains("Legacy.Idle")
                                && d.message().contains("Legacy$Idle")),
                "one unreadable type is expected to reach the model under more than "
                        + "one guessed spelling; if it no longer does, re-measure the "
                        + "claim that recovery is spelling-dependent");
    }

    @Test
    void recoveryAdmitsOnlyTheSpellingThePermitsClauseWrote() {
        // The measurement the whole recovery rests on, and the one thing about it
        // that is a question about SPOON rather than about SealFSM: membership now
        // admits the qualified name Spoon guessed for an unresolved permits
        // reference, so the safety of that depends entirely on whether Spoon can
        // produce the same guess for a use site denoting something else. That is
        // measured here rather than argued, on three variants of one hierarchy
        // that differ by a single import line. See
        // src/test/resources/unreadmember/README.md.
        String root = "src/test/resources/unreadmember/";

        // (1) The intended case. Same package, no import — the only shape `permits`
        // actually allows outside a named module — so both guesses are
        // `samepkg.Amber` and the edge comes back.
        StateMachine samePkg = single(new Analyzer().analyze(modelOf(root + "samepkg")));
        assertEquals(Set.of("Red", "Amber"), stateIds(samePkg));
        assertTrue(hasResolved(samePkg, "Red", "Amber"),
                "a use site in the permitted type's own package must recover");
        assertEquals(Set.of("Amber"), samePkg.statesWithUnreadDeclaration());
        assertEquals(2, samePkg.transitionsViaUnreadDeclaration());

        // (2) THE FABRICATION CONTROL, and the load-bearing one. `import ext.Amber;`
        // makes `new Amber()` denote a foreign type, and it is the only way a simple
        // name in this package can. Spoon honours the import and guesses `ext.Amber`,
        // which does not equal the permits spelling, so membership declines it and
        // the edge stays unresolved. Were it admitted, the tool would publish a
        // RESOLVED edge to a type that is not the state — a fabrication, and the one
        // failure mode the soundness invariant forbids outright. Delete one import
        // line from this fixture and it becomes case (1), which is why the two are
        // held together.
        StateMachine imported = single(new Analyzer().analyze(modelOf(root + "explicitimport")));
        assertFalse(hasResolved(imported, "Red", "Amber"),
                "an import naming a foreign type must not be recovered as the state: "
                        + "that would resolve an edge to a type the analysis never established");
        assertTrue(imported.transitions().stream()
                        .anyMatch(t -> t.from().equals("Red") && !t.isResolved()),
                "and it must be recorded unresolved rather than dropped");
        assertEquals(1, imported.transitionsViaUnreadDeclaration(),
                "only the gap edge out of Amber touches an unread state here");

        // (3) The opposite error, kept because a limitation that is measured is
        // worth more than one discovered later. A wildcard import leaves Spoon
        // unable to qualify the name at all, so the use site arrives as a bare
        // `Amber` and matches nothing — yet JLS 7.5.2 makes the same-package type
        // shadow that import, so the reference really IS the state and the edge is
        // real. Recovery is partial by construction, in this exact way.
        StateMachine wildcard = single(new Analyzer().analyze(modelOf(root + "wildcardimport")));
        assertFalse(hasResolved(wildcard, "Red", "Amber"),
                "a degraded guess must not be matched by resemblance to the simple name");
        assertTrue(wildcard.transitions().stream()
                        .anyMatch(t -> t.from().equals("Red") && !t.isResolved()),
                "the missed edge is a recorded gap, never a silent drop");
    }

    @Test
    void aSynthesisedRecordMemberIsNotAResolutionFailure() {
        // The narrowing that makes the diagnostic usable, and the one this change
        // was measured into rather than assumed. Spoon synthesises a record's
        // accessors, and the implicit `this` in a generated body carries a type
        // reference built from the bare simple name with no package, so it never
        // binds — one per record component. Counting those reported resolution
        // failures on four fixtures whose sources are entirely present, and told
        // three correctly-rejected event alphabets that their rejection might be a
        // resolution failure. A diagnostic that cries wolf trains a reader to
        // ignore the channel, which costs more than it can pay back.
        //
        // Every fixture here is record-heavy and completely readable, so each must
        // be silent. isImplicit() is what separates them: source position does NOT
        // — a type pattern's reference reports no valid position while being a real
        // failure — so a regression to the position test fails here.
        for (String fixture : List.of("examples/lcp_automation", "examples/cancellation",
                "examples/ffmpeg", "examples/guardforms", "examples/retrystate",
                "examples/http2-stream-claude")) {
            ExtractionResult r = new Analyzer().analyze(modelOf(fixture));
            assertFalse(mentionsResolution(r),
                    fixture + " is fully readable, so no synthesised record member may "
                            + "be reported as a type-resolution failure");
        }
    }
}
