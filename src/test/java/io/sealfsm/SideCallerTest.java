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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F39: a method that CALLS the per-state methods is not thereby the place their
 * arms are walked. F3 excluded every transition method some other transition
 * method invoked, on the ground that it is folded into its caller. Two things
 * were assumed and neither was asked: that the caller is walked at all (F36 never
 * walks a site whose result is not installed), and that the call can be folded
 * (a virtual call with one body per state cannot). A table-driven test calling
 * {@code state.onPurchase()} took a real 14/14 vending machine to 0/0.
 *
 * <p>Found on a harvested project with its test sources in {@code --src}; the
 * fixture holds one per-state shape fixed and varies what calls it.
 */
class SideCallerTest {

    private static CtModel modelOf(String path) {
        Launcher launcher = new Launcher();
        launcher.addInputResource(path);
        launcher.getEnvironment().setComplianceLevel(17);
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.buildModel();
        return launcher.getModel();
    }

    private static StateMachine machine(ExtractionResult r, String name) {
        return r.machines().stream().filter(m -> m.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no machine " + name));
    }

    private static Set<String> resolved(StateMachine m) {
        return m.transitions().stream().filter(Transition::isResolved)
                .map(t -> t.from() + " --" + t.event() + "--> " + t.to())
                .collect(Collectors.toSet());
    }

    private static List<String> messages(ExtractionResult r, String where) {
        return r.diagnostics().stream().filter(d -> d.where().endsWith(where))
                .map(ExtractionResult.Diagnostic::message).toList();
    }

    /**
     * The side table's result is data, so it is not walked — and it must take
     * neither the overrides (a virtual call it cannot fold) nor {@code reset} (a
     * static call it could fold, if it were walked) with it.
     */
    @Test
    void anUnwalkedCallerDoesNotSilenceWhatItCalls() {
        StateMachine barrier = machine(new Analyzer().analyze(modelOf("examples/sidecaller")), "Barrier");
        assertEquals(Set.of(
                "Down --onCoin--> Up",
                "Down --onPass--> Down",
                "Down --onForce--> Jammed",
                "Up --onCoin--> Up",
                "Up --onPass--> Down",
                "Up --onForce--> Jammed",
                "Jammed --onForce--> Down",
                "Down --null--> Down",      // BarrierService.reset, installed by the driver
                "Up --null--> Down",
                "Jammed --null--> Down"), resolved(barrier));
        assertEquals(10, barrier.transitions().size(), "no gap: every arm is attributed");
    }

    /**
     * An installed dispatcher IS walked, but {@code s.onOrder()} can run three
     * bodies, so it folds into nothing. Its overrides are walked on their own, and
     * each of its arms adds no edge: in whatever state {@code s} is, the
     * transition is that state's override, already recorded.
     */
    @Test
    void aVirtualCallIntoWalkedOverridesIsADelegationNotAGap() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/sidecaller"));
        StateMachine kiosk = machine(r, "Kiosk");
        assertEquals(Set.of(
                "Idle --onOrder--> Serving",
                "Idle --onServe--> Idle",
                "Idle --onClose--> Shut",
                "Serving --onOrder--> Serving",
                "Serving --onServe--> Idle",
                "Serving --onClose--> Shut",
                "Shut --onClose--> Shut"), resolved(kiosk));
        assertTrue(kiosk.transitions().stream().allMatch(Transition::isResolved),
                "no <unknown> -> ? standing for arms whose transitions are already edges");
        assertTrue(messages(r, "Kiosk").stream().anyMatch(m -> m.contains("(F39)")
                        && m.contains("s.onOrder()") && m.contains("s.onClose()")),
                "every delegated call is named");
    }

    /**
     * The delegation rule's receiver control: a per-state method called on a
     * local holding a first transition's result is two transitions composed,
     * whose source is unknown. It stays unresolved; only the {@code "pull"} arm,
     * which hands over the site's own state, is delegated.
     */
    @Test
    void aCallOnAnotherStateValueStaysAGap() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/sidecaller"));
        StateMachine lever = machine(r, "Lever");
        assertEquals(Set.of(
                "Off --onPull--> On",
                "Off --onPush--> Off",
                "On --onPull--> Off",
                "On --onPush--> On"), resolved(lever));
        assertEquals(5, lever.transitions().size(), "the composed call is recorded, never suppressed");
        List<String> diag = messages(r, "Lever");
        assertTrue(diag.stream().anyMatch(m -> m.startsWith("1 call(s) [s.onPull()]") && m.contains("(F39)")),
                "exactly the selector's call is delegated: " + diag);
        assertTrue(diag.stream().anyMatch(m -> m.contains("[mid.onPull()]") && m.contains("F29")),
                "the local receiver is a refused fold, not a delegation: " + diag);
    }

    /**
     * The delegation rule's reassignment control: the receiver IS the selector by
     * declaration, but the method overwrote it with a first transition's result
     * before the call. Still two transitions composed; still a gap.
     */
    @Test
    void aReassignedSelectorIsNotTheSitesState() {
        ExtractionResult r = new Analyzer().analyze(modelOf("examples/sidecaller"));
        StateMachine crank = machine(r, "Crank");
        assertEquals(Set.of(
                "Low --onHold--> Low",
                "Low --onTurn--> High",
                "High --onHold--> High",
                "High --onTurn--> Low"), resolved(crank));
        assertEquals(5, crank.transitions().size(), "the composed call is recorded, never suppressed");
        assertTrue(messages(r, "Crank").stream().noneMatch(m -> m.contains("(F39)")),
                "nothing is delegated");
    }
}
