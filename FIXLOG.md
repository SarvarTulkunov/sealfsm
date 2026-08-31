# FIXLOG — the dispatch/commit axis separation

One entry per stage. Each records what changed, what it was measured against,
and — where a stage claims a capability gain — which golden files moved and
which `(DispatchLocus, CommitForm)` pair became reachable.

The faithfulness instrument throughout is `scripts/capture-golden.sh`, which runs
the CLI over every fixture directory into its own output tree. `.dot` and
`.scxml` are the contract; `summary.txt` is captured beside them and is where an
intended gain shows up first.

---

## Stage 0 — independent fixes

### Already present at HEAD (verified, not re-implemented)

Three of the five stage-0 items are on `master` already, each as a numbered
finding with its own fixture and its own regression test. Re-implementing them
would have been a rewrite of working code against a spec written from an older
snapshot, so each was verified in place instead:

| stage-0 item | already at HEAD as | where |
|---|---|---|
| `alwaysTerminates` handles `CtThrow` and an all-arms-terminating `CtSwitch` | **F14** | `TransitionExtractor.alwaysTerminates` / `switchAlwaysTerminates`, fixture `examples/throwguards` |
| `isReassigned` compares declaration identity, falls back to the name, reports the fallback | **F13** | `TransitionResolver.isReassigned` + `nameOnlyReassignmentChecks`, fixture `examples/scopedlocals` |
| `detectInitialState` rule 1 requires unanimity | **F15** | `Analyzer.detectInitialState`, fixture `examples/rivalseeds` |

The existing implementations are strictly stronger than the stage-0 wording in
two places, and both differences are deliberate:

* `alwaysTerminates` does not accept *any* switch whose arms terminate. It
  additionally requires the switch to be **exhaustive** and to contain **no
  `break`** (JLS §14.22). Dropping either turns the predicate into a
  fabrication: a non-exhaustive switch falls through on an unmatched selector,
  and a `break` resumes control exactly at the fall-through this predicate
  denies. `examples/throwguards` carries a negative control for each.
* `isReassigned`'s name fallback answers **reassigned**, i.e. it costs a
  resolvable read rather than inventing an unresolvable one, and emits a
  diagnostic naming the variable.

### Unresolved-type counter — present as **F23**, and kept stratified

`TypeResolutionAudit` already collects every unresolved `CtTypeReference` in one
model-wide pass. It is reported at three severities rather than one, and I did
**not** flatten it to the requested single model-level `WARN`:

* model level, **INFO** — count plus a sample;
* per hierarchy, **WARN** — an unresolved *permitted subtype*, or an unresolved
  name sharing a simple name with a member;
* on a rejection, **WARN** — so "no transition producer found" and "I could not
  read the types" stop being the same output.

The reason is the one `Analyzer.reportModelResolution` states in place: run over
a real project with `--src src/main/java`, *every* third-party type is
unresolved and almost none of it matters. A `WARN` there is noise on every
invocation, and a severity that does not discriminate is one a reader learns to
skip — which would cost the per-hierarchy warning its whole value. Say the word
and it is a one-line change.

### Implemented in this stage

* **`--classpath <cp>` / `--cp`** — repeatable, split on the platform path
  separator, passed to `Launcher.getEnvironment().setSourceClasspath`.
  `setNoClasspath(true)` **stays on with it**: the two are not alternatives.
  noClasspath is what lets an incomplete classpath degrade into a guessed
  qualified name instead of aborting the build, and no realistic invocation
  supplies every transitive dependency. `--classpath` shrinks the set of
  references that must be guessed; F23's audit reports whatever is left.
* **`--explain`** — for every **rejected** sealed root, each classifier
  predicate and why it failed.
  * Implemented as `classify(root, model, Consumer<String> trace)`, with the
    old two-argument form delegating to it. It is a side channel on the real
    decision procedure, **not** a second procedure that re-derives the verdict
    for display: this codebase refuses two notions of one thing everywhere else
    (one `chainOf`, one commit predicate, one `StateNaming`) for the standing
    reason that the copy drifts, and a report contradicting the verdict it
    explains is worse than no report.
  * Rejections only. An acceptance already names the predicate that carried it,
    in the reason text printed for every machine; a rejection names only the
    predicate that ran last, and "no transition producer found" reads the same
    whether seven predicates were evaluated or one.
  * The carrier predicate is reported by **which conjunct failed** — no carrier
    methods at all, carrier methods on only one member, or carrier methods that
    only ever produce their own declaring type. It is one boolean to the
    classifier and three different situations to a reader.
  * Printed regardless of `--quiet`, on the same reasoning as the unread-
    declaration footnote: `--quiet` suppresses findings about the run, and this
    was explicitly asked for.

### Acceptance

`mvn test` — **167/167 green**. `diff -r target/golden-base target/golden-stage0`
— **empty**, all 40 fixtures, `.dot`, `.scxml` and `summary.txt` alike.

---

## Stage 1 — introduce the types, change no behaviour

New package `io.sealfsm.detect.dispatch`:

* **`DispatchLocus`** — `CENTRALIZED_SWITCH`, `INSTANCEOF_CHAIN`,
  `POLYMORPHIC_OVERRIDE`, `FUNCTIONAL_CALLABLE`, with `encoding()` mapping the
  four onto the two-position `StateMachine.Encoding`. The mapping is
  many-to-one on purpose: a switch and an `instanceof` chain are one dispatch
  written two ways, and reporting four positions where the thesis claims two
  would put the spelling of a construct into the axis that says where dispatch
  lives.
* **`DispatchArm`** — `(fromSimpleName, body, guard, eventLabel)`. `null`
  from-state means "reached in more than one state, or in one the analysis could
  not determine" — never a stand-in for the first state we thought of. The
  javadoc pins F17 in the type: a type test that SELECTS the state belongs in
  `fromSimpleName`, never in `guard`.
* **`DispatchSite`** — `(locus, host, node, arms)`. `node` is the discrimination
  itself, not the host body, because walking a field-mutation host whole reads
  its trailing `return this.state;` as a producer with no attributable source.
  `hostKey()` is `declaringType#signature`, the key hosts are deduped on
  everywhere else in the codebase.
* **`Commit`** — `(form, value, at)`. `value` may be null: a chain commits inside
  each branch, so the classifier can answer "this dispatch commits by field
  mutation" before any particular branch is walked.

Two verbatim moves, with the old names kept as delegations so no call site
outside the package changed:

* `DispatchCommitDetector.commitFormOf` → **`CommitClassifier.classify`**, plus
  `commitOfTarget` → `CommitClassifier.ofTarget`, which `commitFormOf` calls and
  which the chain path and the extractor also ask. Splitting them would have left
  two definitions of "what counts as a commit".
* `CarrierTransitionDetector.nestsHierarchyValue` and `composesFromOwnParts` →
  **`CompositionVeto`**, together with the F20 helper family they are written in
  terms of (`isCurrentState`, `containsHierarchyValue`, `buildsFromHierarchy`,
  `readsCurrentState`, `bindsPatternVariable`, `discriminates`, `sameVariable`,
  `isHierarchyValue`, `argumentsOf`, `isReceiver`). Moving the entry points
  without the helpers would have left the rule split across two files with the
  interesting half still owned by one locus's detector.
  `DispatchCommitDetector`'s two ternary-aware wrappers became
  `CompositionVeto.nestsThroughTernary` / `composesThroughTernary`.

### One behaviour change, and it is a defect fix, not part of the refactor

`TransitionExtractor.findMutators` returned
`Collections.newSetFromMap(new IdentityHashMap<>())`. Identity is the right
*equality* there — Spoon gives `CtElement` deep structural equality, so two
distinct mutators with identical bodies would dedupe into one — but
`IdentityHashMap` iterates in identity-hash order, which varies between JVM runs.
Those names reach a diagnostic, so **the same jar over the same sources printed
`[engage, assume]` on one run and `[assume, engage]` on the next**. Found by the
golden capture, which is what it is for.

Now a `List` in `getElements` (source) order; each method appears once, so there
was nothing to dedupe. `target/golden-base` was re-captured from HEAD **with only
this fix applied**, so the byte-identity gate for the stages ahead is against a
reproducible baseline rather than one of two possible orderings.

### Acceptance

`mvn test` — **167/167 green**. `diff -r target/golden-base target/golden-stage1`
— **empty**.

---

## Stage 2 — the recognizers reimplemented over the new types

`DispatchFinder` is now the **single recognition surface**. Five discovery
routes, one return type, and not one of them asks what the branches commit:

| route | locus | arms |
|---|---|---|
| `overrideSites` | `POLYMORPHIC_OVERRIDE` | one per permitted subtype declaring the method; from-state = the declaring subtype, **exact**, no data flow |
| `carrierSites` | `POLYMORPHIC_OVERRIDE` | same, for a method returning a carrier — a different commit, not a different dispatch |
| `centralizedMethodSites` | `CENTRALIZED_SWITCH` | the body as one unattributed arm (the discrimination may be a switch, a chain, or several) |
| `producerSites` | `CENTRALIZED_SWITCH` / `INSTANCEOF_CHAIN` | one per `CtCase`, or one per chain link plus the residual |
| `functionalSites` | `FUNCTIONAL_CALLABLE` | the callable body |

`switchSites` is the locus-only half of what `DispatchCommitDetector.find`
answers as a fused pair, and is what 3a and 3b compose against. Everything it
excludes is a statement about the LOCUS and never about the commit: a
discrimination inside a lambda belongs to `FUNCTIONAL_CALLABLE` (one body, one
walker), one with no enclosing method is an initializer.

**`InstanceofChainFinder` is not a stub, and must not be.** The stage plan has it
returning empty until 3c, but F17 already landed on `master`: chains are
recognised, walked, and pinned by `examples/chaindispatch`. Stubbing it would
have deleted a working capability and four fixtures' worth of edges, which is a
regression dressed as a refactor. It is `producerSites`, keyed on whether the
dispatch node is a `CtIf`.

`StateMachineClassifier.classify` and `detectEncoding` now decide over
`DispatchFinder.Sites`. `centralizedReason` counts distinct `site.hostKey()`
rather than re-deriving `declaringType#signature` locally.

`TransitionExtractor.extract` obtains sites **once** and iterates them, routing
each through `walkAt(site, route, commit, walk)`. Two consequences:

* the extractor no longer runs four recognizers of its own, so its notion of "a
  transition method" cannot drift from the classifier's — they now read the same
  list;
* **`carrierMode` and `mutationMode` are gone.** They were modes on the walker:
  a caller set one, walked, and cleared it in a `finally`, and every predicate
  reading them was really asking "which recognizer am I running for?". That is
  now a `WalkSite(route, locus, commit)` set from the site being walked, so the
  claim and the body cannot come apart and no mode can be left set across a site.
  `inCarrier()` and `inMutationFallback()` replace the two reads.

`route` is deliberately **not** redundant with `locus`. The F2 mutation fallback
has no locus at all — it is not a discrimination but a scan of the methods that
write the state field — and it is the route, not the commit form, that licenses
reading a state-field write as a produced successor: a `FIELD_MUTATION` commit
found at a real dispatch is already claimed by that dispatch's own walk.

The reflective type-pattern reading moved to `CasePatterns`, shared by the finder
and the walker. The recognizer that decides which state an arm matches and the
walker that attributes an edge to it must not be able to disagree.

### What was NOT done, and why

The plan says "rewrite `TransitionExtractor` to a single entry point that
iterates sites and arms". The entry point is single and site-driven; the
**per-locus walk procedures were kept**, and I recommend keeping them.

They are not four spellings of one walk. `walkCarrier` deliberately does *not*
fold inter-procedurally — that is the carrier scope line, `carrierMode`'s real
job — while `walk` does, under a depth budget. `walkTypeChain` must thread a
residual across links because the `else` has to know which states are left;
`walkSwitch` has no residual because its arms are disjoint by construction.
`walkFunctional` carries a from-*set* rather than a from-state. Merging them
yields one walker with four flags, which is the arrangement just removed, under a
new name. The axes worth separating were *recognition* (locus) and *commit*, and
those are separated; extraction procedure is a third thing, and the sites are
what let each walker be selected by data instead of by a hard-coded pairing.

### Acceptance

`mvn test` — **167/167 green**. `diff -r target/golden-base target/golden-stage2`
— **empty**. No re-baselining.

---

## Stage 3 — widening, one commit per axis value

### Already present at HEAD (verified, not re-implemented)

Four of the six stage-3 items are on `master`, each with its own fixture and
tests. As in stage 0, they were verified in place:

| item | already at HEAD as | where |
|---|---|---|
| 3c `InstanceofChainFinder` for named methods | **F17** | `DispatchCommitDetector.chainOf` / `addChainProducer`, `TransitionExtractor.walkTypeChain`, fixture `examples/chaindispatch` |
| 3d delete the H-typed-parameter requirement | **F19** | `findCentralizedTransitionMethods` now asks `discriminatesState`, fixture `examples/statefuldriver` |
| 3e re-offer nested sealed subtypes of a rejected root | present | `SealedHierarchyDetector.permittedSealedSubtypes` + the `Analyzer` worklist, fixture `examples/nestedroots` |
| 3f summarise a helper whose returns live in a `CtSwitch` | **F18** | `collectReturnsInto` is gone; the fold reuses `walk`, fixture `examples/hiddenreturns` |

Three carry a refinement the stage-3 wording does not, and each is load-bearing:

* **3c** — a chain additionally needs **two discriminated branches** and one
  selector throughout. An `if` is a far weaker signal than a `switch` over a
  sealed type: one type test is a *check*, not a dispatch, and a method testing
  two unrelated H values is not one chain. `chaindispatch.RelayBoard.reset()`
  pins the price — a single committing type test contributes no edge.
* **3d** — dropping the parameter requirement with nothing in its place admits
  every factory and accessor in the model. `discriminatesState` carries the input
  load, the commit check carries the codomain load, and the parameter keeps a
  *scope* job: a host handed the state has its whole body walked, one that only
  returns H has only its discrimination walked.
* **3e** — only an **ABSTENTION** releases the nested subtypes. The compositional
  veto is a verdict about the data type, and a member of a recursive data type is
  still one. `nestedroots.Node` is the control.

### 3a — `CommitForm.CARRIER_RETURN`, at every locus  (implemented)

`CommitClassifier.carrierComponentOf(R, H)` returns the single hierarchy-typed
component of `R`, else `null`. A "component" is a non-static field — Spoon models
a record's components as fields, so both spellings are covered without depending
on the record API, which has moved across versions. Three `null` answers, each a
deliberate refusal: R is itself in H (that is `VALUE_RETURN`, already handled); R
was never read (F11 — an empty field list on an unread declaration is evidence of
nothing); R has **several** hierarchy-typed components (which slot is the
successor is undecidable, and picking one by field order fabricates a resolved
edge). Nothing keys on a name — not the type's, not the field's.

In `CommitClassifier.classify`, a returned value whose host return type is
outside H but has a carrier component is `CARRIER_RETURN`. **The exhaustive-fold
guard survives intact**: a fold into a type with no hierarchy-typed slot is
rejected by exactly the codomain test that rejected it before.

`TransitionExtractor` unwraps such a value through `handleCarrierValue` — the
same one-level rule, shared rather than restated, so this is a new
`(locus, commit)` cell and not a second carrier implementation free to drift.

**The ordering of the unwrap is load-bearing, and getting it wrong scored 0/10.**
Placed *above* the inter-procedural fold, `case Initial i -> fromInitial(event)`
is a call whose type is the carrier: unwrapping first finds no hierarchy-typed
argument in `(event)` and records a gap — for every arm, on a machine whose whole
relation lives in its helpers. Placed *below* it, the helper's own returns arrive
as the carrier constructions they are and unwrap.

The fold's guard was widened to match: a helper returning the same carrier the
dispatch commits through is producing a state too, one slot further in. The
POLY_CARRIER scope line is **untouched**, and is now keyed on `POLY_CARRIER`
explicitly rather than on "any carrier" — it was a statement about the
`walkCarrier` path, which never had a fold to disable, and exporting it to a
centralized locus would mean the same source, refactored in nothing but its
return type, loses its entire relation.

**Measured.** Unit tests: `src/test/resources/carrierdispatch/` holds three
switches over one hierarchy on one class, differing only in what they fold into —
the case, a fold into a record with **no** hierarchy slot (the exhaustive-fold
control), and a fold into one with **two** (the ambiguity control). Only the
first is a producer; 3/3 edges resolved end to end.

Corpus: **exactly one fixture changed**, and it changed from lost to recovered.

| fixture | before | after |
|---|---|---|
| `examples/lcp_automation_chatgpt` | rejected — "no transition producer found", 0 machines | CENTRALIZED_DISPATCH / CARRIER_RETURN, 10 states, **111/111 resolved** |

That is a 10-state RFC 1661 LCP machine the tool previously reported as *not a
state machine at all*. It is also the corpus's strongest new validation point:
`examples/lcp_automation` is the same RFC recovered at 113/113 through
VALUE_RETURN delegation, so the corpus now holds one specification implemented
twice, independently, under two different commit forms, and recovers essentially
the same relation from both.

The other 39 fixtures are **byte-identical**. `allExamplesTogether`'s
commit-form completeness assertion gained `CARRIER_RETURN` — its purpose is that
the corpus exercises every position of the axis, and it now does.

**Second fixture still needed.** Per the working agreement, the independently
sourced fixture that exercises this same generality is to be supplied rather than
invented. `examples/lcp_automation_chatgpt` arrived as the corpus fixture by
accident of the widening, and `src/test/resources/carrierdispatch/` is a unit-test
resource I wrote, not independent evidence. **Requested: a real-world sealed
hierarchy whose centralized dispatch returns a carrier** — ideally one where the
carrier also holds actions or output symbols, since that is why the idiom exists.

### Acceptance

`mvn test` — **171/171 green** (4 new). One golden changed, and it is a
capability gain: a hierarchy that was rejected is now extracted.
