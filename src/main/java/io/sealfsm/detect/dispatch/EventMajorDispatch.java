package io.sealfsm.detect.dispatch;

import io.sealfsm.detect.SpoonCompat;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtAbstractSwitch;
import spoon.reflect.code.CtAssignment;
import spoon.reflect.code.CtCase;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtInvocation;
import spoon.reflect.code.CtSwitch;
import spoon.reflect.code.CtSwitchExpression;
import spoon.reflect.code.CtVariableAccess;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnum;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtParameter;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The <b>Σ-major</b> (event-major) discrimination: a switch over the <em>input</em>
 * whose arms install a hierarchy value.
 *
 * <pre>{@code
 *   void consumeFrame(FrameType frameType) {      // the selector is the EVENT
 *       switch (frameType) {
 *           case GOAWAY   -> connState = new Closed();     // ... and the arm
 *           case SETTINGS -> connState = new Active();     //     commits a STATE
 *       }
 *   }
 * }</pre>
 *
 * <p>This is the transition table written <b>transposed</b>: every recognizer in
 * {@link DispatchFinder} asks where the <em>state</em> is discriminated, and here
 * it is not discriminated at all — the arm labels are Σ, and the successor is
 * installed without the current state ever being matched. A hierarchy dispatched
 * only this way therefore matched no recognizer, was rejected as "no transition
 * producer found", and — because the candidate channel keys on the same
 * state-discrimination — reported <b>no states either</b>, though its
 * {@code permits} clause names them exactly.
 *
 * <h2>Why this is evidence for the CANDIDATE channel and never for a machine</h2>
 * A Tier 2 machine's contract is one unresolved edge per dispatched arm <em>with
 * a known source state</em>. A Σ-major arm has no source state: nothing in it
 * establishes which member of H the program was in. Publishing such a dispatch as
 * a machine would either fabricate sources or emit a relation sourced entirely at
 * {@code <unknown>}, and the tiers exist precisely so that a gap in attribution is
 * not dressed as a result. So this recognizer answers only the question the
 * candidate channel asks — <em>is this hierarchy discriminated somewhere, with a
 * commit, such that naming its states is a report about the program?</em> — and
 * the machine path never consults it. It can only ADD candidates.
 *
 * <p>For the same reason it is deliberately <b>not</b> a {@link DispatchLocus}
 * value. That axis classifies where the STATE is discriminated; a Σ-major switch
 * discriminates the input, so it is not a position on that axis but a different
 * kind of evidence, and giving it one would make it reachable from the walkers.
 *
 * <h2>The three requirements, and what each one rejects</h2>
 * <ol>
 *   <li><b>The selector is a CLOSED type outside H</b> — an {@code enum} or a
 *       sealed hierarchy. An alphabet is what makes the arms a table; a
 *       {@code switch (int opcode)} or {@code switch (String name)} is the shape
 *       of every parser and every configuration decoder in Java, and admitting
 *       those would make the channel meaningless. The cost is stated rather than
 *       hidden: an {@code int}-coded event alphabet is not reached.</li>
 *   <li><b>At least two arms commit a hierarchy value.</b> One arm installing an
 *       H value is a <em>factory</em> keyed on a discriminator, not a table. The
 *       threshold is the one {@link DispatchCommitDetector} already applies to an
 *       {@code instanceof} chain, for the same reason: a single branch is a check,
 *       and discriminating between cases is what makes it a dispatch.</li>
 *   <li><b>The host holds a hierarchy value</b> — an H-typed parameter, or an
 *       H-typed field on its declaring type. A transition function is Q × Σ → Q,
 *       so Q must be an <em>input</em>; a factory {@code Shape make(Kind k)} has
 *       no Q in and is rejected here. This is the weaker sibling of
 *       {@code DispatchCommitDetector.discriminatesState}, which asks the same
 *       question one step stronger (handed the state AND discriminating it).</li>
 * </ol>
 *
 * <p>The commit itself is <b>not</b> relaxed. It is proven exactly as it is at
 * every other locus — a write to an H-typed field, a recognised
 * {@link MutatorRecognizer mutator} call, or the k = 1 {@link CommitProbe} one
 * callee deep — so a Σ-major switch folding into a {@code String} yields nothing,
 * which is {@code examples/voidfold}'s guarantee restated at this locus.
 *
 * <p>A {@code return} is deliberately not one of the accepted commits: a host that
 * returns H and takes or reads H is already recognised by
 * {@code StateMachineClassifier.findCentralizedTransitionMethods}, which walks it
 * and recovers the relation properly (its Σ-major switch folds into the per-state
 * helpers). Accepting returns here would shadow that path with a weaker answer.
 */
public final class EventMajorDispatch {

    private EventMajorDispatch() {
    }

    /**
     * One Σ-major dispatch.
     *
     * @param dispatch      the switch itself
     * @param host          the method containing it
     * @param selectorType  the qualified name of the input type being discriminated
     * @param committingArms how many arms were shown to install a hierarchy value
     * @param viaCallee     whether any of those commits was found only by opening
     *                      a callee body (the k = 1 probe)
     */
    public record Site(CtAbstractSwitch<?> dispatch, CtMethod<?> host, String selectorType,
                       int committingArms, boolean viaCallee) {

        /** How the candidate report names this site. */
        public String describe() {
            return "EVENT_SWITCH over " + simple(selectorType) + " @ "
                    + (host.getDeclaringType() == null
                            ? host.getSimpleName()
                            : host.getDeclaringType().getQualifiedName() + "#" + host.getSimpleName())
                    + " (" + committingArms + " committing arm(s)"
                    + (viaCallee ? ", commit proven one callee deep" : "") + ")";
        }

        private static String simple(String qualified) {
            int i = qualified.lastIndexOf('.');
            return i < 0 ? qualified : qualified.substring(i + 1);
        }
    }

    /** The result of a scan: the sites found, and the callees that could not be read. */
    public record Result(List<Site> sites, Set<String> unreadable) {
        public Result {
            sites = List.copyOf(sites);
            unreadable = Set.copyOf(unreadable);
        }

        public boolean isEmpty() {
            return sites.isEmpty();
        }
    }

    /**
     * Every Σ-major dispatch over the model that commits a value of {@code root}'s
     * hierarchy.
     */
    public static Result find(CtType<?> root, CtModel model, Set<String> hierarchy) {
        List<Site> out = new ArrayList<>();
        Set<String> unreadable = new LinkedHashSet<>();
        String rootQn = root.getQualifiedName();
        for (CtSwitch<?> sw : model.getElements(new TypeFilter<>(CtSwitch.class))) {
            consider(sw, hierarchy, rootQn, out, unreadable);
        }
        for (CtSwitchExpression<?, ?> sw : model.getElements(new TypeFilter<>(CtSwitchExpression.class))) {
            consider(sw, hierarchy, rootQn, out, unreadable);
        }
        return new Result(out, unreadable);
    }

    private static void consider(CtAbstractSwitch<?> sw, Set<String> hierarchy, String rootQn,
                                 List<Site> out, Set<String> unreadable) {
        CtTypeReference<?> sel = selectorType(sw);
        if (sel == null) return;
        if (hierarchy.contains(sel.getQualifiedName())) return;    // that is the state-major case
        if (!isClosedAlphabet(sel)) return;

        CtMethod<?> host = sw.getParent(CtMethod.class);
        if (host == null) return;
        if (!holdsHierarchyValue(host, hierarchy)) return;

        int committing = 0;
        boolean viaCallee = false;
        for (CtCase<?> arm : sw.getCases()) {
            if (commitsDirectly(arm, hierarchy, rootQn)) {
                committing++;
                continue;
            }
            CommitProbe.Result probe = CommitProbe.of(List.of(arm), hierarchy, rootQn);
            unreadable.addAll(probe.unreadable());
            if (probe.commit() != null) {
                committing++;
                viaCallee = true;
            }
        }
        if (committing >= 2) {
            out.add(new Site(sw, host, sel.getQualifiedName(), committing, viaCallee));
        }
    }

    /**
     * Does this arm install a hierarchy value where the analysis can see it —
     * a write to an H-typed target, or a call to a recognised mutator?
     *
     * <p>Both questions are asked of the shared recognizers rather than restated,
     * so "what counts as a commit" stays one definition across every locus.
     */
    private static boolean commitsDirectly(CtCase<?> arm, Set<String> hierarchy, String rootQn) {
        for (CtAssignment<?, ?> asg : arm.getElements(new TypeFilter<>(CtAssignment.class))) {
            if (CommitClassifier.isCommitTarget(asg.getAssigned(), hierarchy)) return true;
        }
        for (CtInvocation<?> inv : arm.getElements(new TypeFilter<>(CtInvocation.class))) {
            if (MutatorRecognizer.commitOfCall(inv, hierarchy, rootQn) != null) return true;
        }
        return false;
    }

    /**
     * A closed input alphabet: an {@code enum}, or a sealed hierarchy.
     *
     * <p>Answering from the DECLARATION, never from the reference's spelling: an
     * unresolved reference carries a guessed qualified name and no declaration, and
     * guessing "closed" from a name would let an unreadable import decide whether a
     * hierarchy gets a candidate.
     */
    private static boolean isClosedAlphabet(CtTypeReference<?> ref) {
        CtType<?> decl = ref.getTypeDeclaration();
        if (decl == null) return false;
        return decl instanceof CtEnum<?> || SpoonCompat.isSealed(decl);
    }

    /**
     * Does the host have a hierarchy value to compute a successor FROM — an H-typed
     * parameter, or an H-typed field on its declaring type?
     *
     * <p>This is the Q × Σ → Q requirement, and it is what separates a transition
     * table from a factory. It is deliberately weaker than
     * {@code discriminatesState}: a Σ-major dispatch does not discriminate the
     * state anywhere, which is the whole reason it reaches only the candidate
     * channel, so requiring discrimination here would reject every site this
     * recognizer exists to find.
     */
    private static boolean holdsHierarchyValue(CtMethod<?> host, Set<String> hierarchy) {
        for (CtParameter<?> p : host.getParameters()) {
            if (p.getType() != null && hierarchy.contains(p.getType().getQualifiedName())) return true;
        }
        CtType<?> owner = host.getDeclaringType();
        if (owner == null) return false;
        for (CtField<?> f : owner.getFields()) {
            if (f.getType() != null && hierarchy.contains(f.getType().getQualifiedName())) return true;
        }
        return false;
    }

    /** The selector's type, or {@code null} when Spoon could not give one. */
    private static CtTypeReference<?> selectorType(CtAbstractSwitch<?> sw) {
        CtExpression<?> sel = sw.getSelector();
        if (sel == null) return null;
        CtTypeReference<?> t = sel.getType();
        if (t != null) return t;
        // A bare variable read whose expression type Spoon left unset still names a
        // declaration, and the declared type is the authority either way.
        return sel instanceof CtVariableAccess<?> va && va.getVariable() != null
                ? va.getVariable().getType() : null;
    }

    /** Every arm body of every site, which is what a further probe would be asked about. */
    public static List<CtElement> armBodies(List<Site> sites) {
        List<CtElement> out = new ArrayList<>();
        for (Site s : sites) {
            out.addAll(s.dispatch().getCases());
        }
        return out;
    }
}
