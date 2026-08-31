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

### 3b — `CommitForm.MUTATOR_ARGUMENT`, at every locus  (implemented)

`MutatorRecognizer.commitsItsArgument(callee, H, rootQn)` — structural end to
end, and nothing in it keys on a name. Exactly one parameter, typed with the
hierarchy **root**, and a body that assigns a hierarchy-typed field an expression
in which *that parameter is the only hierarchy value*. The field is identified by
its declared type through the same `CommitClassifier.ofTarget` rule every other
commit uses; two machines in one model routinely both call their field `state`.

The second clause is the half a plain structural rule misses.
`void restart(Vent previous) { audit(previous); this.state = new Sealed(); }` has
one hierarchy-typed parameter and does write the state field — so "one H-typed
parameter whose body writes an H-typed field" admits it exactly as the F22 word
list did — yet its parameter is the state being *left* and the successor is
chosen by the callee. What licenses reading a call's argument as the target is
that the mutator commits **what it was handed**.

`DispatchCommitDetector` asks it after the syntactic forms decline, so a dispatch
that commits by return or by assignment keeps its classification and this can
only add machines, never re-attribute one.

#### The root-typed narrowing, found by a fixture

First cut required only that the parameter be *in* H. That regressed
`examples/nestedroots`, and the failure is instructive: a permitted subtype may
itself be sealed, so H(child) ⊆ H(parent), and `void setState(Body next)` has a
parameter inside `Message`'s hierarchy too. `Message` went from correctly
abstaining — and re-offering `Body` as a root in its own right — to being
published as a **five-state automaton**, with `Body` then never classified at
all. A sum type that merely *contains* a machine, reported as one.

Requiring the parameter to be the **root** costs nothing real: a mutator installs
the machine's state, so its parameter is the type the state field is declared
with, and a concrete-state parameter could not accept the other states. It is a
type test, not a name test. The same parent/child leak exists in principle for
the other commit forms and no fixture exposes it, precisely because
`nestedroots.Body` is built on the mutation encoding — the one encoding no
producer recognised. Closing it generally means judging a child against its
widest enclosing hierarchy, which is a change to the ownership rule, not to this
recognizer.

#### The dropped gap, also found by a fixture

Making a mutator dispatch a producer means `out` is no longer empty, so the F2
mutation fallback — guarded on "nothing else found anything" — stopped running.
That fallback is the **only** thing that records a mutation commit no dispatch
claimed. On `examples/mutatorshape` that is `restart`, whose commit is real and
whose source state is unknowable, and skipping it took `Vent` from an honest
**1/2** to a clean-looking **1/1** with the gap deleted: a transition dropped
with no unresolved marker, which is the one outcome the record-everything
invariant forbids by name.

The fallback now also runs when every producer found was a `MUTATOR_ARGUMENT`
one, and skips hosts a dispatch already walked (`walkedHosts`), so nothing is
counted twice. `Vent` is back to 1/2 with `restart`'s gap and its diagnostic.

#### The commit axis now reports what it observed

`extractMutationEncoding` announced `FIELD_MUTATION` before walking anything.
That predates `MUTATOR_ARGUMENT` existing — it was the only label available, so
it stood for both "writes the state field" and "hands the state to a mutator".
The form is now recorded at the point a commit is actually walked, so a machine
committing only through `ctx.setState(...)` is no longer filed under a form it
does not use.

#### Measured

**Every transition relation in the corpus is unchanged** — verified edge by edge,
not just by count. Four machines' axis labels became accurate, and one machine is
new:

| fixture | before | after |
|---|---|---|
| `gofcontext.Portal` | MIXED / FIELD_MUTATION, 5/5 | MIXED / **MUTATOR_ARGUMENT**, 5/5 |
| `plumbing-mutation.Hopper` | MIXED / FIELD_MUTATION, 6/6 | **CENTRALIZED_DISPATCH** / **MUTATOR_ARGUMENT**, 6/6 |
| `nestedroots.Body` | MIXED / FIELD_MUTATION, 3/3 | **CENTRALIZED_DISPATCH** / **MUTATOR_ARGUMENT**, 3/3 |
| `mutatorshape.Bolt` | MIXED / FIELD_MUTATION, 3/3 | **CENTRALIZED_DISPATCH** / **MUTATOR_ARGUMENT**, 3/3 |
| `mutatorshape.Vent` | MIXED / FIELD_MUTATION, 1/2 | **CENTRALIZED_DISPATCH** / **FIELD_MUTATION,MUTATOR_ARGUMENT**, 1/2 |

`Vent` reporting both forms is correct and is the sharpest confirmation the split
is real: `restart` writes the field directly, `assume` is a mutator, and the
machine genuinely uses both.

`MIXED` → `CENTRALIZED_DISPATCH` on four of them is the improvement CLAUDE.md's
own "what to work on next" list asks for ("Reconsider MIXED for Portal / Vend.
Both are `@Fsm`-marked mutation machines that predate the commit axis"). They sat
in a bucket labelled "undetermined" only because no recognizer saw their
dispatch; now one does.

**Second fixture still needed.** Requested: a real-world sealed hierarchy using
the GoF Context idiom where the driver ALSO has a value-returning transition
method — the shape whose mutator commits are lost today because the fallback
never runs. No corpus fixture has it, so that gain is currently argued rather
than measured.

### Acceptance

`mvn test` — **174/174 green** (3 new). Five goldens changed; every relation is
identical and only the axis labels moved, except `lcp_automation_chatgpt` from 3a.

---

## Stage 4 — precision, applied once at the commit

Both items are on `master` already, each as a numbered finding with its own
fixture. As before, they were verified in place rather than rewritten — and 4a
was additionally **re-asserted at the locus 3a opened**, which is where the plan
says the fabrication would appear.

| item | already at HEAD as | where |
|---|---|---|
| 4a `neverReturnsNormally` at the commit, every locus | **F21** | `callsNonReturningHelper` at `handleCarrierValue`'s entry, and `neverReturnsNormally` in the fold; fixture `examples/throwcarrier` |
| 4b bound the compositional veto | **F20** | `CompositionVeto` + `CarrierTransitionDetector.composesItself`; fixture `examples/retrystate` |

### 4a, re-asserted at the new cell

`src/test/resources/carrierreject/` is a centralized table committing through a
carrier — the cell 3a opened — with the F9 question asked there. Three arms,
differing only in what the helper's body can do:

* `case Ready r -> new Move(new Busy(), "start")` — an ordinary carrier arm,
  resolves.
* `case Busy b -> Undefined.illegal(b, event)` — **no edge at all**, not even an
  unresolved one. `illegal` is syntactically indistinguishable from a carrier
  factory: a call whose own type is outside the hierarchy carrying a
  hierarchy-typed argument. Nothing in the expression separates them; only the
  body does, and JLS §8.4.7 makes "no `return` anywhere in a non-void method" a
  proof. Its argument is the *current state*, which is how such a helper is
  nearly always called, so the fabrication would be a **self-loop — one per
  specification-undefined cell**, and a real transition table has many.
* `case Spent s -> Undefined.recover(s, event)` — the **negative control**, and
  the sharp half. `recover` throws on one path and returns on another, so it CAN
  produce a successor and its edge must survive. A "contains a `throw`" rule
  would delete a real transition with no unresolved marker. It survives, and it
  arrives carrying `[!(event < 0)]` — F14 attaching the rejection branch's
  negated test at the same site.

Reported: 3 states, 2/2, plus the diagnostic "1 call(s) to a helper that cannot
return normally … contributed no transition — the arms holding them are undefined
inputs, not unresolved targets". Suppression is *reclassification*, never a silent
drop.

### 4b

Unchanged and re-verified: `examples/treebuilder` is still REJECTED on both
acceptance paths, and `examples/retrystate`'s four hierarchies still separate the
veto's bound from its trigger (one self-composing production is sufficient alone;
anything else needs ≥2 nested productions across ≥2 distinct members, and a lone
non-self-composing production is downgraded to a per-edge unresolved rather than a
verdict about the type).

### Acceptance

`mvn test` — **175/175 green**. The tree-builder negative fixture is still
rejected; its golden is byte-identical.

---

## Stage 5 — re-baseline

`target/golden-base` (HEAD + the `findMutators` determinism fix) was compared
against the final tree. **Six goldens changed across the whole refactor, on five
fixtures**, and every one is accounted for:

| golden | stage | what changed | which (locus, commit) is now reachable |
|---|---|---|---|
| `lcp_automation_chatgpt/` (new `.dot`, `.scxml`, summary) | **3a** | rejected as "no transition producer found" → CENTRALIZED_DISPATCH, 10 states, **111/111** | `(CENTRALIZED_SWITCH, CARRIER_RETURN)` — previously unreachable: the carrier recognizer required a per-subtype override, the switch recognizer required an H-typed commit |
| `plumbing-mutation/Hopper.*`, summary | **3b** | MIXED / FIELD_MUTATION → CENTRALIZED_DISPATCH / MUTATOR_ARGUMENT; **relation identical, 6/6** | `(CENTRALIZED_SWITCH, MUTATOR_ARGUMENT)` — previously reachable only through the whole-hierarchy F2 fallback, i.e. only when nothing else was found |
| `nestedroots/Body.*`, summary | **3b** | same relabelling; **relation identical, 3/3** | same |
| `mutatorshape/Bolt.*`, `Vent.*`, summary | **3b** | same relabelling; **relations identical, 3/3 and 1/2**. `Vent` reports FIELD_MUTATION **and** MUTATOR_ARGUMENT, which is correct — `restart` writes the field, `assume` is a mutator | same |
| `gofcontext/summary.txt` | **3b** | FIELD_MUTATION → MUTATOR_ARGUMENT; **relation identical, 5/5** | the commit axis now reports what it observed rather than the only label that existed before the split |

No golden lost an edge, and no golden's transition relation changed at all —
verified edge by edge, not by count. The only new machine is
`lcp_automation_chatgpt`, and it was previously reported as not a state machine.

`MIXED` → `CENTRALIZED_DISPATCH` on four machines is the item CLAUDE.md's own
"what to work on next" list asks for: they sat in a bucket labelled "undetermined"
only because no recognizer saw their dispatch.

---

## The deliverable — every (DispatchLocus × CommitForm) pair

`R` reachable and exercised · `r` reachable, no fixture exercises it ·
`—` not reachable · `n/a` not a meaningful combination.
**Bold** marks a cell this work opened.

| | VALUE_RETURN | FIELD_MUTATION | LOCAL_ACCUMULATOR | POLY_CARRIER | CARRIER_RETURN | MUTATOR_ARGUMENT |
|---|---|---|---|---|---|---|
| **CENTRALIZED_SWITCH** | R | R | R | n/a | **R** | **R** |
| **INSTANCEOF_CHAIN** | R | R | r | n/a | **R** | **R** |
| **POLYMORPHIC_OVERRIDE** | R | R* | n/a | R | n/a | R* |
| **FUNCTIONAL_CALLABLE** | R | — | — | — | — | — |

### Cell by cell

**CENTRALIZED_SWITCH** — `VALUE_RETURN` `examples/door`, `http2-stream-claude`,
`lcp_automation` (113/113). `FIELD_MUTATION` `http2-stream-gemini`, `barefield`.
`LOCAL_ACCUMULATOR` `examples/accumulator`. **`CARRIER_RETURN`** newly reachable —
`examples/lcp_automation_chatgpt` (10 states, 111/111, previously rejected
outright) and `src/test/resources/carrierdispatch`. **`MUTATOR_ARGUMENT`** newly
reachable as a *dispatch* — `plumbing-mutation`, `mutatorshape`,
`nestedroots.Body`; previously reachable only through the whole-hierarchy F2
fallback, i.e. only when nothing else was found.

**INSTANCEOF_CHAIN** — `VALUE_RETURN` and `FIELD_MUTATION` from F17
(`examples/chaindispatch`). `LOCAL_ACCUMULATOR` is reachable — `commitFormOfChain`
asks `CommitClassifier.ofTarget`, which answers `LOCAL_ACCUMULATOR` for an H-typed
local — but **no fixture writes a chain that accumulates into a local**, so it is
untested. **`CARRIER_RETURN`** newly reachable
(`carrierdispatch.ChainRouter.flip`, 2/2); it was still missing after 3a, because
`commitFormOfChain` asked only whether the host returns H, and closing it is what
makes "at every locus" true rather than "at three of them".
**`MUTATOR_ARGUMENT`** newly reachable — `mutatorCommitIn` is asked of a chain's
branches on the same terms as a switch's arms.

**POLYMORPHIC_OVERRIDE** — `VALUE_RETURN` `examples/traffic`. `POLY_CARRIER`
`examples/tcp` (44/44), `throwcarrier`, `valueforms`.
`CARRIER_RETURN` is `n/a` here **only because `POLY_CARRIER` already is it**: the
two are one commit mechanism at two loci, kept as separate enum values purely for
continuity with the thesis's published table. Collapsing them is a one-line change
plus a re-baseline and is the author's call.
`LOCAL_ACCUMULATOR` is `n/a`: at an override the method's *return* is the commit,
so a local inside it is an intermediate, not an installation.
`FIELD_MUTATION` and `MUTATOR_ARGUMENT` are marked **R\*** — reachable, and
exercised by `gofcontext.Portal` (5/5) and `examples/nondeterministic`, but
through the **F2 mutation fallback, which has no locus at all**. It is not a
discrimination; it is a scan of the methods that write the state field, and it is
modelled as `Route.MUTATION_FALLBACK` with a null locus for exactly that reason.
Reading those cells as "the override locus commits by mutation" would be
generous: what is really true is that a per-state callback mutating a context is
recovered, and no dispatch was involved.

**FUNCTIONAL_CALLABLE × everything but VALUE_RETURN — the one axis pair still
fused, and it is fused in the extractor rather than in the recognizer.**
`DispatchFinder.functionalSites` finds the locus independently of any commit, and
`CommitClassifier` would answer for it; but `TransitionExtractor.extractFunctional`
hard-codes `commitForms.add(VALUE_RETURN)`, and `walkFunctional` — which carries a
from-*set* rather than a from-state — only ever treats a returned value as a
production. A lambda that commits by assigning a field or by calling a mutator
therefore contributes nothing. Closing it means teaching `walkFunctional` the
commit branches `walk` already has, over a from-set instead of a from-state; it is
a contained change and no corpus fixture demands it, so it is left named rather
than done untested.

### Two honest caveats on the table

* Reachability is a property of the code, established here by reading the
  composition (`DispatchFinder` → `CommitClassifier`/`MutatorRecognizer` →
  `TransitionExtractor`) and confirmed by fixtures wherever an `R` appears. The
  single `r` is argued, not measured.
* The locus axis is **not** in the tool's output — the summary table reports
  `StateMachine.Encoding`, which has two positions, and four loci map onto those
  two. So the corpus evidence above pairs a fixture with a locus by construction
  (which recognizer produced its site), not by reading it off a report. Exposing
  the locus is a reporting change worth making if the validation chapter wants to
  stratify by it; the mapping already exists as `DispatchLocus.encoding()`.

---

## F24 — a correction to this branch's headline result

**The 111/111 reported for `examples/lcp_automation_chatgpt` in the Stage 3a
entry above was wrong. 109 of those 111 edges were fabricated.** The entry is
left standing rather than rewritten, because the sequence is the finding.

### How it surfaced

Rendering the two LCP outputs side by side. `lcp_automation` draws a connected
automaton; `lcp_automation_chatgpt` drew **ten isolated nodes**, each with a pile
of self-loops and almost nothing joining them. A relation that is 109/111
self-loops is not a state machine, and `Initial --UP--> Initial` contradicts RFC
1661 §4.1 outright (Initial + Up = Closed). The score said 111/111; the picture
said the score was meaningless.

### Diagnosis, by ablation rather than by reading the fixture

| ablation | `lcp_automation` | `lcp_automation_chatgpt` | conclusion |
|---|---|---|---|
| disable rule 4 entirely | — | 111/111 → **2/111** | all 109 rest on rule 4 |
| unresolvable declaration no longer a selector | 113/113 | 111/111 | not a binding failure |
| require a unique root-typed parameter | 113/113 | 111/111 | not the arity case |
| suppress rule 4 inside any fold | **113/113** | **2/111** | the fold is where it fires — and `lcp_automation` does not need it there |
| map arguments → parameters (the fix) | **113/113** | **2/111** | the callee parameter never received the matched state |

The fourth and fifth rows together are what make this a fix rather than a trade:
`lcp_automation` recovers the same RFC through the same fold and is
**unaffected**, so the restriction removes fabrications without touching a
legitimate folded self-loop.

### The fix

`TransitionExtractor.selectorParamsOf` maps the call's arguments onto the
callee's parameters positionally and keeps those the caller demonstrably handed
the current state, decided by **`CompositionVeto.isCurrentState`** — the shared
predicate for the three ways a walk knows the from-state, asked rather than
restated. `TransitionResolver.foldSelectors` carries that set for the duration of
the fold; rule 4 applies to a callee **parameter** only when it is in the set. A
pattern binding inside the callee is exempt — it is the discriminated value of
the switch that bound it.

### Measured

Corpus: **one fixture changed**, `lcp_automation_chatgpt`, 111/111 → **2/111**.
All 39 others byte-identical, including every fixture that folds
(`lcp_automation` 113/113, `hiddenreturns` 8/9, `nonreturning` 4/6,
`dhcp-client-*`, `http2-stream-*`). Two regression tests, deliberately a pair:
one asserts no resolved self-loop survives in the chatgpt fixture, the other that
`lcp_automation`'s real stay-put cells do — so the fix cannot decay into a
blanket suppression.

### What this changes about the Stage 3a claim

3a's **recognition** gain stands: a hierarchy reported as "not a state machine"
is now extracted, with 10 exact states and 111 recorded cells. Its **recall** is
2/111, not 111/111. The corpus does **not** contain "one specification recovered
twice under two commit forms"; it contains one specification whose two
implementations recover at 113/113 and 2/111, and that gap is itself the result:
a per-state helper handed the matched state folds completely, one reached with
the state passed some other way does not.

### The half still open

At a **top-level dispatch** `isSelectorBinding` still accepts any root-typed
parameter, so `H step(H current, H fallback, E e)` returning `fallback` remains a
fabricated self-loop. Same shape of fix — compare against the selector the walk
is holding — and no corpus fixture now exercises it.
