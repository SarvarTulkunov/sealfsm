package io.sealfsm;

import io.sealfsm.analyze.GuardAnalysis;
import io.sealfsm.detect.SealedHierarchyDetector;
import io.sealfsm.detect.SpoonCompat;
import io.sealfsm.detect.StateMachineClassifier;
import io.sealfsm.detect.StateMachineClassifier.Classification;
import io.sealfsm.detect.StateMachineClassifier.Rejection;
import io.sealfsm.detect.TypeResolutionAudit;
import io.sealfsm.extract.StateExtractor;
import io.sealfsm.extract.TransitionExtractor;
import io.sealfsm.model.CommitForm;
import io.sealfsm.model.ExtractionResult;
import io.sealfsm.model.State;
import io.sealfsm.model.StateMachine;
import io.sealfsm.model.StateNaming;
import io.sealfsm.model.Transition;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtConstructorCall;
import spoon.reflect.code.CtLocalVariable;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.TypeFilter;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Orchestrates the full pipeline over a built Spoon model:
 * detect sealed roots → classify → extract states → extract transitions →
 * detect the initial state. Produces an {@link ExtractionResult} carrying both
 * the machines and the diagnostics needed for an honest validity argument.
 */
public final class Analyzer {

    private final SealedHierarchyDetector detector = new SealedHierarchyDetector();
    private final StateMachineClassifier classifier = new StateMachineClassifier();
    private final StateExtractor stateExtractor = new StateExtractor();

    /**
     * When set, every REJECTED root carries the classifier's predicate-by-predicate
     * trace into the result ({@code --explain}).
     *
     * <p>Only rejections. An acceptance already names the predicate that carried
     * it, in the reason text printed for every machine; a rejection names only the
     * one that ran last, and "no transition producer found" is the same sentence
     * whether nine predicates were evaluated or one.
     */
    private boolean explain;

    /** Fluent so a caller can write {@code new Analyzer().explaining(true)}. */
    public Analyzer explaining(boolean on) {
        this.explain = on;
        return this;
    }

    public ExtractionResult analyze(CtModel model) {
        ExtractionResult result = new ExtractionResult();

        // What the model could not resolve, gathered once. Models are built with
        // setNoClasspath(true), so an unresolvable type still reaches the analysis
        // as a reference with a guessed qualified name, and every membership test
        // reads that as "not in the hierarchy" - the same answer a genuinely
        // foreign type gives. Without this the two are indistinguishable in the
        // output, and a result thinned by missing sources is reported as a
        // finding about the program.
        TypeResolutionAudit resolution = TypeResolutionAudit.of(model);

        List<CtType<?>> roots = detector.findSealedRoots(model);
        if (roots.isEmpty()) {
            result.info("model", "no sealed hierarchies found");
            reportModelResolution(result, resolution);
            return result;
        }
        reportModelResolution(result, resolution);

        // A worklist rather than a plain iteration over `roots`: a rejected root
        // may still CONTAIN a machine. findSealedRoots withholds a sealed
        // permitted subtype because its parent claims it as a composite state,
        // but that claim is only good if the parent turns out to be a machine.
        // When it does not, the child has to be offered as a root in its own
        // right — otherwise `sealed interface Message permits Header, Body`,
        // with Body itself a machine, loses Body entirely and the only thing
        // reported is that Message was skipped.
        Deque<CtType<?>> pending = new ArrayDeque<>(roots);
        Set<String> claimed = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            CtType<?> root = pending.pollFirst();
            if (!claimed.add(root.getQualifiedName())) continue;

            List<String> trace = explain ? new java.util.ArrayList<>() : null;
            Classification c = trace == null
                    ? classifier.classify(root, model)
                    : classifier.classify(root, model, trace::add);
            if (!c.isStateMachine()) {
                result.info(root.getQualifiedName(),
                        "skipped — " + c.reason() + reoffer(root, c, pending, claimed));
                reportResolution(root, result, resolution, true);
                if (trace != null) result.explain(root.getQualifiedName(), trace);
                continue;
            }
            reportResolution(root, result, resolution, false);

            StateMachine machine =
                    new StateMachine(root.getSimpleName(), root.getQualifiedName(), c.encoding());

            // States and the ids naming them are produced together: two permitted
            // subtypes may legally share a simple name, so ids can only be assigned
            // once the complete state set is known. The same naming then governs
            // every transition endpoint, so an edge cannot name a state that does
            // not exist — or, worse, silently merge with an edge between two other
            // states because both endpoints collapsed to one spelling.
            StateExtractor.Result states = stateExtractor.extract(root);
            for (State s : states.topLevelStates()) {
                machine.addTopLevelState(s);
            }
            for (String dup : machine.duplicateStateIds()) {
                result.warn(root.getQualifiedName(),
                        "two states share the id '" + dup + "' — their edges will be "
                                + "indistinguishable in the output");
            }
            if (states.naming().hasCollisions()) {
                result.info(root.getQualifiedName(),
                        "disambiguated state name(s) " + states.naming().collidingSimpleNames()
                                + " — the permits clause names distinct types sharing a simple name");
            }

            Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);
            // Every member of an accepted machine is one of its STATES, so none of
            // them may later be re-offered as a root. Normally vacuous (a nested
            // sealed type is never in `roots` to begin with), it matters when a
            // sealed type extends two sealed interfaces and only one of them is a
            // machine: without this the type would be reported twice, once as a
            // composite state and once as a machine of its own.
            claimed.addAll(hierarchy);
            TransitionExtractor te =
                    new TransitionExtractor(hierarchy, root.getQualifiedName(), states.naming());
            for (Transition t : te.extract(root, model)) {
                machine.addTransition(t);
            }
            // F4: register the closed-world event alphabet Σ, so it is complete
            // even for events the transition function ignores (no edge carries them).
            for (String symbol : te.alphabet()) {
                machine.addAlphabetSymbol(symbol);
            }
            // The commit axis, orthogonal to encoding and to successor form: how
            // each recovered successor was installed. Reported so recall can be
            // stratified by idiom rather than pooled per encoding.
            for (CommitForm cf : te.commitForms()) {
                machine.addCommitForm(cf);
            }
            // A member whose declaration was never read contributes the edges that
            // MENTION it (the permits clause names it) but not the ones it
            // produces. That asymmetry is recorded here, never left to read as an
            // absence of transitions.
            recordUnreadDeclarations(root, machine, states.naming(), te.dispatchedStates(), result);
            // A state the dispatch matched but from which nothing is produced is
            // absorbing — RFC 9113's `Closed`, whose every arm throws. A state the
            // dispatch never matched is not: that is a recall gap, and
            // markTerminalStates refuses to promote it.
            machine.markTerminalStates(te.dispatchedStates());
            for (String d : te.diagnostics()) {
                result.warn(root.getQualifiedName(), d);
            }
            // F5: guard-level defect detection over the recovered edges —
            // overlapping guards (possible nondeterminism) and numeric coverage
            // gaps (possible missing transition). Diagnostics only: no edge is
            // removed or merged, preserving the record-everything invariant.
            for (String w : GuardAnalysis.check(machine)) {
                result.warn(root.getQualifiedName(), w);
            }

            initialStateEvidence = null;
            detectInitialState(root, machine, model, states.naming(), result)
                    .ifPresentOrElse(
                            init -> {
                                machine.setInitialState(init);
                                // Name the weakest rule's evidence, so a reader can
                                // separate a proven start point from an inferred one.
                                if (initialStateEvidence != null) {
                                    result.info(root.getQualifiedName(),
                                            "initial state '" + init + "' inferred from "
                                                    + initialStateEvidence
                                                    + " — weaker evidence than a state field "
                                                    + "or an unreachable-source state");
                                }
                            },
                            () -> result.warn(root.getQualifiedName(),
                                    "initial state could not be determined"));

            // The suffix is empty for every input whose hierarchy was fully readable,
            // so this line is unchanged on the whole corpus. Where it is not empty,
            // it separates two strengths of evidence that must not be pooled: an
            // edge matched against a declaration, and one matched through a name
            // Spoon guessed for a declaration nobody read.
            long viaUnread = machine.transitionsViaUnreadDeclaration();
            result.info(root.getQualifiedName(),
                    "extracted — " + c.reason() + "; "
                            + machine.allStates().size() + " states, "
                            + machine.resolvedTransitionCount() + "/"
                            + machine.transitions().size() + " transitions resolved"
                            + (viaUnread > 0
                                    ? "; " + viaUnread + " of them touch a state whose declaration "
                                            + "was never read and are therefore weaker evidence "
                                            + "than the rest"
                                    : ""));
            result.addMachine(machine);
        }
        return result;
    }

    /**
     * Model-level note that some type references did not resolve.
     *
     * <p>Deliberately INFO, and deliberately a count rather than a list. Run over
     * a real project with {@code --src src/main/java}, every third-party and
     * otherwise off-classpath type is unresolved and almost none of it matters; a
     * WARN there would be noise that trains a reader to ignore the channel. The
     * severity is raised only where a failure actually touches a hierarchy under
     * consideration — see {@link #reportResolution}.
     */
    private static void reportModelResolution(ExtractionResult result, TypeResolutionAudit audit) {
        if (audit.isEmpty()) return;
        result.info("model",
                audit.size() + " type reference(s) did not resolve under noClasspath "
                        + audit.summary(8)
                        + " — these read as 'not in the hierarchy' wherever they appear, so any "
                        + "analysis of code mentioning them is incomplete. Harmless for types no "
                        + "machine uses; pass the missing sources with --src if any belong to one");
    }

    /**
     * Raise the resolution failures that touch <em>this</em> hierarchy, so a thin
     * result can be attributed to unreadable input rather than read as a finding
     * about the program.
     *
     * <p>Two failures are reported, and they differ in kind:
     *
     * <ul>
     *   <li><b>A permitted subtype that did not resolve.</b> The severe one,
     *       because it lands on the claim the tool makes <em>exactly</em>.
     *       {@link StateExtractor} reads states from the permits references, so
     *       the state is still enumerated by name — but its declaration was never
     *       read, so a sealed or {@code enum} member's children are silently
     *       absent, and since the membership set is built from resolved
     *       declarations it is absent from that too, which costs every edge
     *       mentioning it.</li>
     *   <li><b>An unresolved type sharing a simple name with a member.</b> Weaker,
     *       and explicitly hedged: it does not prove the reference IS that member.
     *       It is reported because it is the only available evidence that a thin
     *       result may be a resolution failure, and the name is consulted to pick
     *       the TEXT of a diagnostic — never an edge, a state or a classification,
     *       which is what keeps it clear of the rule that no analysis decision
     *       keys on a name.</li>
     * </ul>
     *
     * <p>Neither changes a verdict. Promoting an unresolved reference into the
     * hierarchy on a name match would fabricate a resolved edge to a state the
     * analysis never established — the one outcome the soundness invariant forbids
     * outright — so membership still answers "no" and the failure is recorded
     * instead. That is the same trade the tool already makes for an unresolved
     * successor: record the gap, never guess past it.
     */
    private void reportResolution(CtType<?> root, ExtractionResult result,
                                  TypeResolutionAudit audit, boolean rejected) {
        String where = root.getQualifiedName();
        // The same set the membership recovery admits, asked from the same helper:
        // a report and a recovery that each derived "which members are unread"
        // would eventually name different sets, and the disagreement would be a
        // state warned about but not recovered, or recovered but not warned about.
        Set<String> unresolvedStates = new TreeSet<>(
                StateMachineClassifier.unresolvedMemberNames(root));
        if (!unresolvedStates.isEmpty()) {
            result.warn(where,
                    "permitted subtype(s) " + TypeResolutionAudit.summarize(unresolvedStates, 8)
                            + " did not resolve — their declarations were never read. They are "
                            + "still enumerated as states, and because the permits clause is what "
                            + "names them, edges TO them are recovered wherever the use site was "
                            + "spelled the same way; but any child states of a sealed or enum "
                            + "member are missing, and any transition producer declared INSIDE "
                            + "them was never examined. State enumeration is exact only over a "
                            + "source set that contains the whole hierarchy");
        }

        Set<String> memberNames =
                new LinkedHashSet<>(StateMachineClassifier.hierarchyQualifiedNames(root));
        memberNames.addAll(unresolvedStates);
        List<String> resembling = audit.resembling(TypeResolutionAudit.simpleNames(memberNames))
                .stream().filter(n -> !unresolvedStates.contains(n)).toList();
        if (!resembling.isEmpty()) {
            result.warn(where,
                    "unresolved type reference(s) " + TypeResolutionAudit.summarize(resembling, 8)
                            + " share a simple name with a member of this hierarchy — if any of "
                            + "them IS that member, every recognizer read it as a foreign type and "
                            + "the edges mentioning it are missing");
        }

        if (rejected && (!unresolvedStates.isEmpty() || !resembling.isEmpty())) {
            result.warn(where,
                    "this rejection may be a type-resolution failure rather than a verdict: a "
                            + "member whose declaration was never read has no methods to "
                            + "recognise, so a hierarchy can abstain purely because its producers "
                            + "were not in --src, and a use site Spoon spelled differently from "
                            + "the permits clause is still indistinguishable from a foreign type. "
                            + "Re-run with the whole hierarchy in --src before recording it as "
                            + "'not a state machine'");
        }
    }

    /**
     * Record, as an explicit unresolved edge, the outgoing transitions of a state
     * whose declaration the analysis never read.
     *
     * <p>This is the half that stops
     * {@link StateMachineClassifier#hierarchyQualifiedNames}'s recovery from
     * buying a better-looking score than the input supports. Admitting the
     * permits clause's spelling recovers the edges that MENTION such a state, and
     * under a centralized dispatch that is the entire relation: the unread files
     * are pure data, and {@code examples/door} minus its three state files reports
     * exactly the five transitions the machine has. Under a polymorphic encoding
     * it is not, because the transition logic lives in the file that was not read
     * — {@code examples/traffic} minus {@code Yellow.java} recovers
     * {@code Green → Yellow} and can never recover {@code Yellow → Red}. Reported
     * as 2/2 that is a perfect score on a machine missing a third of its relation:
     * a transition dropped behind a clean-looking n/n with no unresolved marker,
     * which is the one outcome the record-everything invariant forbids by name.
     * Reported as 2/3 it is a recovered edge beside a visible gap, which is what
     * the input actually supports. The recovery and this record are therefore one
     * change and must not be separated.
     *
     * <p><b>Eligibility asks "did any producer EXAMINE this state?"</b>, and it is
     * answered by {@code dispatchedStates} — deliberately the same signal
     * {@link StateMachine#markTerminalStates} already trusts to separate an
     * absorbing state from a recall gap, rather than a second notion of the same
     * thing. A state a dispatch arm matched had its successors computed by a host
     * the analysis did read; a state nothing matched has no outgoing edges only
     * because none could be recovered.
     *
     * <p>The residual is named in the diagnostics rather than hidden: a state a
     * centralized dispatch matched, whose unread file ALSO declared a producer of
     * its own, gets no marker. Nothing in the readable source separates that from
     * a state whose file holds only data — the readable half is identical in both
     * — so what can honestly be said is said, in the INFO line.
     */
    private static void recordUnreadDeclarations(CtType<?> root, StateMachine machine,
                                                StateNaming naming, Set<String> dispatched,
                                                ExtractionResult result) {
        Set<String> unread = StateMachineClassifier.unresolvedMemberNames(root);
        if (unread.isEmpty()) return;

        String where = root.getQualifiedName();
        Set<String> unexamined = new TreeSet<>();
        Set<String> examined = new TreeSet<>();
        Set<String> unreadIds = new LinkedHashSet<>();
        for (String qn : unread) {
            String id = naming.idFor(qn);
            if (id == null) continue;
            unreadIds.add(id);
            if (dispatched.contains(id)) {
                examined.add(qn);
                continue;
            }
            machine.addTransition(Transition.unresolved(id, null, null,
                    "declaration of " + qn + " was never read, so its transition producers "
                            + "— if it declares any — were not examined"));
            unexamined.add(qn);
        }

        // Provenance, carried in the model rather than only in this report: every
        // edge touching one of these states was matched through a name Spoon
        // guessed, which is weaker evidence than a match against a declaration the
        // tool read. Marked for ALL unread members, examined or not — the flag is
        // about what was read, not about what a dispatch happened to do with it.
        for (State st : machine.allStates()) {
            if (unreadIds.contains(st.id())) st.setDeclarationUnread(true);
        }

        if (!unexamined.isEmpty()) {
            result.warn(where,
                    "state(s) " + TypeResolutionAudit.summarize(unexamined, 8)
                            + " were enumerated from the permits clause, but their declarations "
                            + "were never read and no dispatch examined them, so their outgoing "
                            + "transitions are unknown. One unresolved edge is recorded per state, "
                            + "so the gap is counted rather than read as an absence of transitions");
        }
        if (!examined.isEmpty()) {
            result.info(where,
                    "state(s) " + TypeResolutionAudit.summarize(examined, 8)
                            + " did not resolve, but a dispatch the analysis could read computes "
                            + "their successors, so their outgoing edges are recovered. A producer "
                            + "declared inside the unread declaration itself would not be");
        }
    }

    /**
     * Release a rejected root's nested sealed hierarchies back onto the worklist,
     * returning the text to append to that root's diagnostic (empty when nothing
     * is released).
     *
     * <p><b>Only an abstention releases them.</b> The two rejections mean
     * different things and only one of them is silent about the members:
     *
     * <ul>
     *   <li>{@link Rejection#ABSTAINED} says the recognizers found no transition
     *       producer <em>for this hierarchy</em>. That is not a statement about a
     *       machine declared inside it, so the child gets its own look — and, if
     *       it is rejected too, its own diagnostic naming <em>it</em> rather than
     *       only its parent.</li>
     *   <li>{@link Rejection#VETOED} says the members are composed into one
     *       another. Re-offering there would hand the child a strictly NARROWER
     *       hierarchy in which the composition can be invisible: in
     *       {@code sealed interface Branch extends Node}, a {@code new Wrap(left)}
     *       whose argument is typed {@code Node} nests one hierarchy value inside
     *       another as far as {@code Node} is concerned, but {@code Node} is not
     *       in {@code Branch}'s hierarchy set, so the same expression reads as a
     *       peer production. The veto would silently evaporate and the tree
     *       builder would be reported as an automaton one level down — exactly
     *       the false positive the veto exists to prevent.
     *       {@code examples/nestedroots}' {@code Node}/{@code Branch} is the
     *       control that pins this.</li>
     * </ul>
     *
     * <p>A machine really nested inside a compositional hierarchy is therefore
     * still lost. That is a recall gap and a deliberate one: closing it means
     * judging the child against its WIDEST enclosing hierarchy rather than its
     * own, which is a change to the compositional veto itself, not to this
     * worklist.
     */
    private String reoffer(CtType<?> root, Classification c,
                           Deque<CtType<?>> pending, Set<String> claimed) {
        if (c.rejection() != Rejection.ABSTAINED) return "";
        List<CtType<?>> nested = detector.permittedSealedSubtypes(root).stream()
                .filter(t -> !claimed.contains(t.getQualifiedName()))
                .toList();
        if (nested.isEmpty()) return "";
        pending.addAll(nested);
        boolean one = nested.size() == 1;
        return "; re-offering nested sealed " + (one ? "hierarchy " : "hierarchies ")
                + new TreeSet<>(nested.stream().map(CtType::getQualifiedName).toList())
                + (one ? " as a root in its own right" : " as roots in their own right");
    }

    /**
     * Initial-state heuristics, in priority order:
     * <ol>
     *   <li>a field typed as the hierarchy, initialised with {@code new Concrete()},
     *       when every such field agrees on one state;</li>
     *   <li>the unique state with no resolved incoming edge but ≥1 outgoing edge;</li>
     *   <li>a hierarchy-typed LOCAL initialised with {@code new Concrete()}, when
     *       every such local in the model agrees on one state.</li>
     * </ol>
     * All are reported as heuristic; this is a known weak point and is flagged
     * as such in diagnostics when it fails.
     *
     * <p><b>No rule picks between rival candidates.</b> A model is a set of files
     * with no meaningful order, so "the first one found" is a claim about which
     * file Spoon happened to walk first, dressed up as a claim about the program.
     * Rules 1 and 3 therefore collect ALL candidates and answer only when they
     * are unanimous; a disagreement is reported and falls through to the next
     * rule. The asymmetry is intentional and runs through all three — a machine
     * with NO initial state reports a gap, whereas a wrong one is a fabricated
     * claim, and unanimity is what keeps a rule from guessing.
     *
     * <p>Rule 3 is additionally last because a local is weaker evidence than a
     * field: it is typically a driver's or a test's starting point, not
     * necessarily the machine's. It exists because a dense automaton defeats
     * rule 2 exactly when it is most faithful: in RFC 1661's LCP every state
     * including {@code Initial} has incoming edges, so "no incoming, some
     * outgoing" has no candidate at all.
     */
    private Optional<String> detectInitialState(CtType<?> root, StateMachine machine,
                                                CtModel model, StateNaming naming,
                                                ExtractionResult result) {
        Set<String> hierarchy = StateMachineClassifier.hierarchyQualifiedNames(root);

        // F7: a callable that produces a state on the machine-entry residual has
        // already named the initial state explicitly (an edge from the initial
        // pseudo-state). That is a direct signal, so it wins over the heuristics.
        for (Transition t : machine.transitions()) {
            if (StateMachine.INITIAL_PSEUDO_STATE.equals(t.from())) {
                return Optional.of(StateMachine.INITIAL_PSEUDO_STATE);
            }
        }

        // Rule 1: a hierarchy-typed field seeded with a concrete state — a context
        // object's current-state holder. Unanimity is required for the same reason
        // it is at rule 3: two drivers seeded from different states are rival
        // candidates, and answering with the first one found lets file traversal
        // order decide where the machine starts.
        Set<String> seededFields = new LinkedHashSet<>();
        for (CtField<?> field : model.getElements(new TypeFilter<>(CtField.class))) {
            CtTypeReference<?> ft = field.getType();
            if (ft == null || !hierarchy.contains(ft.getQualifiedName())) continue;
            if (!(field.getDefaultExpression() instanceof CtConstructorCall<?> cc)) continue;
            CtTypeReference<?> init = cc.getType();
            if (init == null || !hierarchy.contains(init.getQualifiedName())) continue;
            if (isSelfSingleton(field, init)) continue;
            seededFields.add(naming.idFor(init.getQualifiedName()));
        }
        if (seededFields.size() == 1) {
            return Optional.of(seededFields.iterator().next());
        }
        if (seededFields.size() > 1) {
            // Sorted, not in encounter order: the decision is now independent of
            // how the model was walked, and the diagnostic reporting it should be
            // too, or the same program still yields different text run to run.
            result.warn(root.getQualifiedName(),
                    "hierarchy-typed state fields disagree on the initial state "
                            + new TreeSet<>(seededFields)
                            + " — abstaining rather than letting source order pick one; "
                            + "falling through to the structural rule");
        }

        // Rule 2: the unique state nothing reaches but something leaves.
        Set<String> hasIncoming = new HashSet<>();
        Set<String> hasOutgoing = new HashSet<>();
        for (Transition t : machine.transitions()) {
            if (t.isResolved() && t.to() != null) hasIncoming.add(t.to());
            hasOutgoing.add(t.from());
        }
        List<String> candidates = machine.allStates().stream()
                .map(State::id)
                .filter(id -> !hasIncoming.contains(id) && hasOutgoing.contains(id))
                .toList();
        if (candidates.size() == 1) {
            return Optional.of(candidates.get(0));
        }

        // Rule 3: a hierarchy-typed local seeded with a concrete state. Requires
        // unanimity across the model, so two drivers starting from different
        // states abstain rather than let source order pick a winner.
        Set<String> seeded = new LinkedHashSet<>();
        for (CtLocalVariable<?> local : model.getElements(new TypeFilter<>(CtLocalVariable.class))) {
            CtTypeReference<?> lt = local.getType();
            if (lt == null || !hierarchy.contains(lt.getQualifiedName())) continue;
            if (!(local.getDefaultExpression() instanceof CtConstructorCall<?> cc)) continue;
            CtTypeReference<?> init = cc.getType();
            if (init == null || !hierarchy.contains(init.getQualifiedName())) continue;
            if (declaredInsideHierarchy(local, hierarchy)) continue;
            seeded.add(naming.idFor(init.getQualifiedName()));
        }
        if (seeded.size() == 1) {
            String only = seeded.iterator().next();
            // Named as the weaker evidence it is, so a reader can tell this apart
            // from a state the structure proved.
            initialStateEvidence = "a hierarchy-typed local seeded with new " + only + "()";
            return Optional.of(only);
        }
        return Optional.empty();
    }

    /**
     * A field whose initialiser constructs its own declaring type holds that
     * type's own instance, which says nothing about where the machine starts.
     * Two shapes reach rule 1 this way, and both are in the corpus:
     *
     * <ul>
     *   <li>a <b>flyweight</b> state constant, {@code static final Armed INSTANCE
     *       = new Armed()} inside {@code Armed} — every state may declare one, so
     *       counting them makes each state a rival candidate;</li>
     *   <li>an <b>enum constant</b>: Spoon models {@code Phase.IDLE} as a
     *       {@code CtEnumValue extends CtField}, typed {@code Phase}, with an
     *       implicit {@code new Phase()} default. A permitted enum is part of the
     *       hierarchy, so every one of its constants looked like a seed.</li>
     * </ul>
     *
     * <p>This exclusion is what makes rule 1's unanimity safe rather than costly.
     * Without it these outvote the one real driver and a machine whose start IS
     * stated in its source reports a gap instead — {@code rivalseeds.Ratchet} and
     * {@code examples/namecollision} both do, the latter reporting that
     * {@code [Mode, Phase, namecollision.Idle]} disagree.
     *
     * <p>Keyed on the constructed type being the DECLARING type, not merely on
     * the field living somewhere in the hierarchy: a constant declared on the
     * sealed root ({@code Signal START = new Idle();}) does name a start state,
     * and a root is abstract, so it can never be its own instance.
     */
    private static boolean isSelfSingleton(CtField<?> field, CtTypeReference<?> constructed) {
        CtType<?> owner = field.getDeclaringType();
        return owner != null && owner.getQualifiedName().equals(constructed.getQualifiedName());
    }

    /**
     * True when {@code e} sits inside one of the hierarchy's own types. A local
     * declared there is a successor under construction — {@code Signal next = new
     * Firing();} inside {@code Armed.on} — not a seed. Rule 3 rests on the local
     * being a driver's or a test's starting point, which is a claim about code
     * OUTSIDE the machine; applied inside it, the rule reports whichever
     * successor some state happens to build as the place the machine begins.
     */
    private static boolean declaredInsideHierarchy(CtElement e, Set<String> hierarchy) {
        for (CtType<?> owner = e.getParent(CtType.class); owner != null;
             owner = owner.getParent(CtType.class)) {
            if (hierarchy.contains(owner.getQualifiedName())) return true;
        }
        return false;
    }

    /**
     * Set by {@link #detectInitialState} when the initial state came from the
     * weakest rule, so the diagnostic can say what it rested on. Null when the
     * state was found structurally (or not at all).
     */
    private String initialStateEvidence;

    // Exposed for completeness checks in tests/tools.
    public boolean isSealed(CtType<?> type) {
        return SpoonCompat.isSealed(type);
    }
}
