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

---

## F25 — the fold discarded the caller's argument bindings

### The invariant that was violated

**A fold must not destroy information the caller already had.** The
inter-procedural summary (F3/F18) walked a callee's body in the caller's
from-context but carried **no binding from the callee's parameters to the
caller's actual arguments**. Any successor that arrived through a parameter read
inside the callee was therefore unresolvable *by construction* — not by a scope
line, not by a budget, but because the analysis had thrown away the only thing
that could have answered.

```java
// caller
case Idle i -> wrap(new Running(), List.of());
// callee
private Carrier wrap(State s, List<Action> a) { return new Carrier(s, a); }
```

The fold enters `wrap`, reaches `new Carrier(s, a)`, takes the hierarchy-typed
slot, and gets `s` — a parameter with nothing bound to it. Unresolved edge.

Note what that means. **Before** entering the callee the successor expression
`new Running()` was in hand at the call site and resolvable; entering discarded
it. The fold was a **lossy** step: each additional hop destroyed information
rather than recovering it.

That is visible as a number, and it is the sharpest evidence in this entry —
the resolved-edge count against the depth budget, whole corpus:

| k | before: trans / resolved | after: trans / resolved |
|---|---|---|
| 1 | 599 / **589** | 599 / **589** |
| 2 | 599 / **482** | 599 / **591** |
| 3 | 600 / **483** | 600 / **594** |
| 4 | 600 / **483** | 600 / **594** |

**Before, allowing one more hop cost 107 edges.** k=1 → k=2 is *monotone
decreasing*, which for a sound approximation is impossible: more analysis
budget cannot license fewer proofs. Declining to fold produced strictly better
results than folding, and the committed default of k = 2 sat on the wrong side
of the cliff. After the fix the curve is monotone non-decreasing and flattens at
k = 3, which is the deepest delegation chain in the corpus. **The budget was
never the bug.** (`MAX_INTERPROC_DEPTH`, the `>=` that tests it, and the
ordering that runs F9 before the fold attempt are all unchanged; the sweep was
run by editing the constant temporarily and reverting it.)

### The fix

A per-frame positional binding map `CtParameter → CtExpression`, pushed and
popped **with** `interProcStack` — the two are one thing, a frame of the fold
being a callee together with what its caller handed it, and keeping them in step
structurally is what stops a binding outliving the body it belongs to.

* `TransitionExtractor.argumentBindings(inv, callee)` builds it, positionally,
  truncating on arity mismatch with `Math.min` exactly as `selectorParamsOf`
  already did.
* `TransitionResolver` holds the frames as a stack and threads a frame index
  through `resolve`. Rule **(5)**, last in `fromVariable`: a parameter present in
  the current frame's map resolves by **recursively resolving the bound caller
  expression**, one frame out, under the existing `seen` set and
  `MAX_INITIALIZER_DEPTH`, in the caller's from-context and guard context.

Binding a parameter to the expression the caller actually passed is **exact**,
not an approximation: it is the argument, not a guess about it. This widens
nothing. It stops the analysis discarding what it already had.

Three restrictions keep it exact, and each is asked of existing code rather than
re-derived:

* **Identity-keyed** (`IdentityHashMap`). Spoon gives `CtElement` deep
  structural equality, so two distinct helpers' identically-spelled parameters
  compare equal and one would stand in for the other. This is the F13 rule,
  unchanged.
* **Not bound when the callee REASSIGNS it** — what such a parameter holds at
  the `return` is no longer the argument. `TransitionResolver.isReassigned`
  answers it, by declaration identity; it gained a declaration-keyed entry point
  so there is still exactly one implementation.
* **A varargs parameter is skipped.** It collects the remaining arguments into
  an array, so the positional match is not the value it holds.

**F24's restriction is now a derived case, not a second mechanism.**
`foldSelectors` — "the callee parameters the caller demonstrably handed the
current state" — is read off this map as the subset whose bound expression
satisfies `CompositionVeto.isCurrentState`. One notion of "what a parameter
holds", not two; a second would eventually disagree with the first, and the
disagreement would be an edge. Both directions are exercised on the corpus:
`examples/factory` takes the positive branch (a parameter bound to the caller's
selector, rule 4 still fires, 3/4 unchanged), `examples/lcp_automation_chatgpt`
the negative (bound to a construction, rule 4 declines and rule 5 resolves it).

### What rule (5) may NOT do, and the one narrowing that is not derived

Resolving a bound argument means resolving an expression in the **caller's** body
— and rule 4 lives there too, licensing "a root-typed parameter read means stay
in the matched state" for *any* parameter. That is a standing
over-approximation at a top-level dispatch (see "The half still open" under F24),
and the fold must not route into it: it reaches that point only when the callee's
own parameter has already been shown **not** to be the current state, so widening
there would contradict what was just established. `fwd(spare)` would become a
proven self-loop on the strength of `spare` being a parameter.

So when the frame index walks below the innermost frame, rule 4 is licensed by
`CompositionVeto.isCurrentState` asked of the **read** rather than of a binding —
the same shared predicate, one level out. Measured: without it,
`fwd(fallback)` in `H step(H current, H fallback, E e)` published a resolved
self-loop; with it, the arm is an explicit gap.
`src/test/resources/foldbinding`'s `Ballast` is the control, and the cost of
getting it wrong is invisible in the score — its other arm resolves either way,
so the machine reads a clean 2/2 with one edge fabricated.

This does **not** close the top-level half of F24's open item. `isSelectorBinding`
still accepts any root-typed parameter at a dispatch, so a direct
`return fallback;` is still a fabricated self-loop; what changed is that the fold
no longer reaches it.

### What is now reachable

A successor **computed at the call site and installed by a callee** — the
forwarding-factory idiom — which was previously unresolvable at every locus and
every commit form. Concretely:

* a bare value forwarder, `H forward(H s) { return s; }`;
* a forwarding factory, `Carrier wrap(H s, List<A> a) { return new Carrier(s, a); }`
  — the shape that makes `CARRIER_RETURN` recall depend on the binding, because
  there the successor is an *argument* to a wrapper and a factory puts it behind
  a parameter read by construction;
* a **three-hop** chain, dispatch arm → per-event helper → forwarding factory,
  where the successor has to survive two frames;
* a **selection** helper, `H pick(H a, H b, boolean c) { return c ? a : b; }`,
  which yields both targets under opposite guards rather than collapsing them or
  choosing by position.

### Fixture

`src/test/resources/foldbinding/` — six hierarchies in one package, holding the
call shape fixed and varying only what the binding is handed. It is a test
resource rather than an `examples/` member because every hierarchy in it is a
single-question probe rather than a corpus specimen; all of it compiles under
`javac`.

| hierarchy | asks | answer |
|---|---|---|
| `Flow` | bare forwarder, selection helper, **reassigned** parameter, **bodiless** callee | 3 states, **4/6** — two proofs, two gaps, both gaps deliberate |
| `Carry` | forwarding factory at `CARRIER_RETURN`, **three hops**, and F9 on a call syntactically identical to the factory | 3 states, **4/4** + 1 suppressed cell |
| `Twin` | **ambiguity on the ARGUMENT side**: two hierarchy-typed arguments into a one-slot vs a two-slot construction, on one class | 2 states, **2/2** — the placed argument only, and no self-loop |
| `Loop` | **termination**: a direct and a mutually recursive forwarder | 2 states, **0/2**, both recorded |
| `Ballast` | rule 4 must not widen on the way back out | 2 states, **1/2** |

The negatives carry the weight. `Carry`'s `reject(new Parked(), event)` is
syntactically indistinguishable from `wrap(new Parked(), ...)` — a call whose own
type is outside the hierarchy carrying a hierarchy-typed argument — and only the
callee's body separates them. **F9 has to be asked first**, and the binding is
what makes that newly load-bearing: before F25 an undefined cell was unresolved
anyway, whereas now accepting it would publish a *resolved* edge on every input
the source rejects, one per cell, usually as a self-loop.

`Twin` is the ambiguity control the corpus lacked on this side. The existing
`carrierdispatch` control covers the component side of a **type**; this is the
**call**. Both dispatches pass two hierarchy-typed arguments to a forwarder — so
the argument list alone cannot say which is the successor — and they are held on
one class so nothing but the forwarder's construction can be what the analysis
reacts to. `one(a, b)` places `a` in a one-slot wrapper and discards `b`: the
binding is by USE, not "every hierarchy-typed argument is a target". `pack(a, b)`
places both in a two-slot wrapper, and neither is published — here the guess
would be visible, because the second slot is the current state and choosing by
argument order would show up as a self-loop the source does not contain.

### Second generality fixture

**PENDING** — to be supplied independently, not derived from
`lcp_automation_chatgpt` (which is what made the first test fail) and not
invented here. It should be a real-world sealed hierarchy whose transition
function delegates through a helper that installs a successor received as a
parameter, sourced separately from the corpus above.

### Measured

Corpus (`scripts/capture-golden.sh`, all 40 fixture directories, 46 machines):

* **one fixture moved**: `examples/lcp_automation_chatgpt`, **2/111 → 111/111**.
  All 39 others byte-identical in `.dot`, `.scxml` and `summary.txt` — including
  every fixture that folds (`lcp_automation` 113/113, `hiddenreturns` 8/9,
  `nonreturning` 4/6, `dhcp-client-*`, `http2-stream-*`, `factory` 3/4,
  `statefuldriver`, `websocket-claude`).
* corpus totals **599 transitions / 482 resolved → 599 / 591**. The transition
  COUNT is flat, which is the check that matters: F9 suppression is unchanged at
  **63 suppressed calls** in every configuration, before and after and at every
  k, so the extra 109 are the same cells resolving, not new cells appearing.
* **no state count moved**, on any machine.
* the three negative-control fixtures still yield **zero machines**
  (`shape`, `treebuilder`, `foreignfold`).
* 183 tests green (178 before, +5).

### The chatgpt fixture, and why 111/111 is a different number this time

F24's entry says of this fixture: *"It briefly reported 111/111, and that number
was 109 fabrications."* It reports 111/111 again. The two are not the same claim,
and the difference is checkable three ways rather than asserted:

1. **The successor-form axis.** The fabrication resolved through rule 4, so it
   was `SELF`. The machine now reports `CONSTRUCTION` and nothing else — the
   fabrication's fingerprint is *absent*, not merely outweighed.
2. **The edge F24 names.** RFC 1661 §4.1 says Initial + Up = Closed. The
   fabrication reported `Initial --UP--> Initial`; the output now reports
   `Initial --UP--> Closed`, and the regression test asserts both halves.
3. **The picture.** The fabricated relation drew ten isolated nodes. Compared
   against `examples/lcp_automation` — the same RFC, written independently, in a
   different idiom — **39 of the 40 recovered (from, to) state pairs agree**.
   Event spellings differ by construction (`RCR_PLUS` vs a guard split on
   `ReceiveConfigureRequest`), so the labels cannot be compared directly, but the
   state relation can, and it does.

### What this changes about the F24 entry's closing claim

F24 concluded: *"The corpus does not contain 'one specification recovered twice
under two commit forms'; it contains one specification whose two implementations
recover at 113/113 and 2/111 … Do not quote the two as agreeing."* That was
correct then and is **superseded**. The gap it described was not a fact about the
idiom — a helper reached with the state passed some other way is foldable after
all — it was the missing binding. The pair now recovers at **113/113 and
111/111** with 39/40 agreement on the state relation, which makes it the second
independent-implementation agreement in the corpus after the http2 pair, and the
larger of the two by an order of magnitude.

The honest qualifier that remains: they are two *implementations* of one
specification, so agreement is evidence about the extractor, not a ground truth.
The 49 cells `lcp_automation_chatgpt` leaves undefined (F9-suppressed) against
`lcp_automation`'s 31 `throw`s is a real difference between the two sources, not
a recall gap in either.

---

## F26 — states were only enumerated on the accepted path, and a commit one call away was invisible

Two independent defects, of different kinds, established by Step 0 of
`PROMPT_decouple_states_from_transitions.md` before either was touched. The full
investigation, with verbatim probe output and file:line references, is
`INVESTIGATION.md`; this entry records the finding as that investigation
established it, not the symptom as it was reported.

### The finding

**(i) Structural coupling.** The tool makes two claims of different strength and
their independence is its central contribution: states come from a
compiler-checked `permits` clause and are **exact**; transitions are recovered by
intra-procedural data flow and are **approximate**. `Analyzer.analyze` derived the
state set only on the accepted path — `StateExtractor` is invoked once, at
`Analyzer.java:121`, *after* the `continue` at `Analyzer.java:108` — so a
hierarchy whose transition producer no recognizer matched reported **zero
states**. Claim 1 had quietly become "states are exact *when transitions
resolve*", which is a materially weaker claim and one the tool does not need to
make. `DebugHarness` confirms it independently: STAGE 4 is never reached for a
rejected root. The states were not extracted and discarded; they were never
extracted.

**(ii) Missing capability.** `CommitClassifier` reads the commit from the
dispatch's *immediate syntactic context*, which is right and is the precision
guard. But a `switch` **statement** whose arms are bare calls has no such
context — Java discards an expression statement's value (JLS §14.8), so there is
no codomain to read, no assignment, no local declaration — and the commit is a
side effect inside the callee. Such a dispatch was not a recognised dispatch at
all.

**(i) is not caused by (ii)**, which is why they are recorded as two findings.
Closing (ii) alone moves one shape and leaves every *other* unrecognised idiom,
plus every genuine non-machine, reporting zero states.

**A third, smaller finding** is (i)'s diagnostic counterpart: `--explain` printed
`FAILED — none found` both for "nothing discriminates this hierarchy" and for
"three dispatches discriminate it and not one commits", because the count it
rendered was the *composed* one (`DispatchFinder.producerSites`, i.e. locus and
commit and veto). The channel could not express the distinction that Tier 3 *is*.

### Step 0, and the branch it selected

Three throwaway probes, same hierarchy and same exhaustive dispatch, varying only
the callee: `returnh` (H-returning helper), `voidh` (void helper writing an
H-typed field), `voidforeign` (void helper writing a `String` field).

| probe | at HEAD | branch it rules out |
|---|---|---|
| `returnh` | **accepted**; 3 states, 0/3, one unresolved edge per arm | **A** — the codomain proof is already held and used. No recognition bug at this cell, and nothing was reimplemented behind a probe |
| `voidh` | abstains, **0 states** | — the genuine gap |
| `voidforeign` | abstains, **0 states**, trace line-for-line identical to `voidh` | **C** — nothing is over-admitted |

`returnh` already produced the Tier 2 contract in full, which is why no part of
Branch A was implemented: a smaller true finding is worth more than a large
speculative one. Branch **B**.

### The fix

Three pieces, and only the third is a widening.

1. **`ExtractionResult.candidates()` and `model/Candidate`** — the decoupling.
   A rejected root that *is* discriminated now has its complete `permits` closure
   enumerated by the same `StateExtractor` call the machine path makes, nesting
   and all, and is reported as **Tier 3**: not a machine, no `.dot`, no `.scxml`,
   no transition relation claimed, and its states named exactly. Two conditions,
   both load-bearing: only an **ABSTENTION** (a veto is a positive verdict about
   the data type and binds on its members — the same distinction that governs the
   re-offer worklist), and only where a **dispatch was actually found** (dropping
   that half makes every sealed type a candidate, at which point the channel
   distinguishes nothing). `examples/shape` is the control for the second.
2. **`--explain` separates locus from commit.** `DispatchFinder.locusSites`
   answers the locus half alone, `DispatchCommitDetector.chainHeads` is its chain
   component and is consumed by `find` rather than duplicated, and the trace now
   ends by naming the tier.
3. **`CommitProbe` — the k = 1 commit-existence probe.** For an arm whose
   statement is a bare call, open the callee's body *once* and ask only *does this
   install a hierarchy value?*, never *which one*. Asked strictly **after** every
   direct rule has declined, so it can only add machines and never re-attribute
   one; `CompositionVeto` still runs after it.

`CalleeBody` was extracted so F9 (`neverReturnsNormally`) and F11 (`isShadow`)
have **one** implementation, called by both `TransitionExtractor` and the probe.
That is the standing rule in this codebase, and here the drift would have been a
body one caller suppresses an edge for and another admits one from.

### What the probe may count as evidence — one clause, and why the other three collapse

The prompt scoped the probe to four questions (return with codomain in H, assign
an H-typed field, assign an H-typed local subsequently committed, hand a value to
a recognised mutator). Three of them are **inert by construction**, and saying so
is more useful than implementing them:

* a **`return`** inside the callee installs nothing, because *this* caller
  discards the value (JLS §14.8). Counting it would admit `describe(state);`
  called for its ignored result;
* an **H-typed local** dies with the frame; and if it is "subsequently committed",
  the committing act is itself a field write in the same body — already seen. A
  second rule for it would be a second notion of one thing;
* a **mutator call** inside the callee needs the *mutator's* body read, which is a
  second hop. The probe's depth is exactly 1 and is independent of the k = 2
  resolution budget. The one-hop spelling (an arm calling the mutator directly) is
  already `MUTATOR_ARGUMENT` and is asked before this.

Also forced: the probe never applies to an arm whose produced **value** is a call.
If that value is committed, the commit is already `DIRECT`; if it is not, the
caller either discards it or routes it outside H, and no callee body can license
either. So the probe's whole added reach is the bare-call-statement shape.

**One narrowing was found by a fixture, not by inspection.** The written field's
declared type must be the hierarchy **ROOT**, not merely a member — the identical
clause `MutatorRecognizer.soleRootParameter` makes, caught by the identical
fixture. A permitted subtype may itself be sealed, so H(child) is a subset of
H(parent), and `BodyContext.setState` writing a `Body`-typed field proved a commit
for `Message`: `examples/nestedroots` published the containing sum type as a
five-state automaton and, because an accepted parent claims its members, **lost
`Body`** — the real machine — altogether. It appeared as a corpus diff on the
first build and is now pinned by `everyExistingGoldenIsUnchanged`.

### The IR

`CommitEvidence` (`DIRECT` / `VIA_CALLEE` / `UNPROVEN`), per machine, weakest-wins
across the sites walked. It is **not a fourth axis**: the three axes classify how
a machine is *written*, this records how much the analysis had to open to see it,
which is a property of the analysis — the same reason `State.isDeclarationUnread`
is not one either. Pooling it with `CommitForm` would let a gap in the inference
hide behind the observation, which is the argument that split `MUTATOR_ARGUMENT`
out of `FIELD_MUTATION`.

`StateMachine.isDetectedEmpty()` is derived (`resolvedTransitionCount() == 0 &&
!transitions().isEmpty()`), so it cannot drift from the edges it describes.

Surfaced as a DOT graph-level comment and an SCXML comment **only when it is not
`DIRECT`**, and Main's Tier 2 footnote likewise. That is the rule the
unread-declaration reporting already follows: on well-formed input every commit is
direct, so an unconditional line would print `DIRECT` on every diagram in the
corpus and say nothing. A line that appears is a line that carries information —
and it is also what keeps every existing `.dot` and `.scxml` byte-identical.

### Which (DispatchLocus x CommitForm) cells this reaches

**None that were unreachable.** This is the honest answer and it is worth stating
plainly, because the shape of the gain is different from every stage above it.
The probe establishes `FIELD_MUTATION` at `CENTRALIZED_SWITCH` and at
`INSTANCEOF_CHAIN`, and both pairs were already reachable — via
`this.state = switch (state)` and via F17's chain respectively. What is new is a
**third value orthogonal to the product**, so the table is multiplied rather than
filled in:

| | CENTRALIZED_SWITCH | INSTANCEOF_CHAIN | POLYMORPHIC_OVERRIDE | FUNCTIONAL_CALLABLE |
|---|---|---|---|---|
| `DIRECT` | R | R | R | R |
| `VIA_CALLEE` | **R** (`examples/voidcommit`) | **r** — reachable, untested | — | — |

`INSTANCEOF_CHAIN` x `VIA_CALLEE` is reachable — `addChainProducer` asks the probe
of `chainBranches(chain)` on exactly the switch's terms — but **no fixture writes
a chain whose branches are bare calls committing inside a callee**, so it is
argued rather than measured. It joins `INSTANCEOF_CHAIN` x `LOCAL_ACCUMULATOR` as
the second cell in that state.

`POLYMORPHIC_OVERRIDE` x `VIA_CALLEE` and `FUNCTIONAL_CALLABLE` x `VIA_CALLEE` are
**not reachable, deliberately**. An override's commit *is* its codomain and a
functional callable's likewise, so at those loci there is no bare-call arm for the
probe to be asked about. Reaching them would mean reading a per-state method's
body for a side effect, which is the F2 mutation fallback's job and is a different
question.

### Fixtures

Six, each in its own package, each compiled by `javac` as part of being a
fixture. `src/test/resources/probe/` is deleted — fixtures 1–3 are the promoted
probes, so nothing is written twice.

| fixture | tier | pins |
|---|---|---|
| `examples/opaquesuccessor` | 2 | 4 states exact, `DIRECT`, 0/4, one unresolved edge per arm with a known source. Successors put out of reach by a `return` inside `synchronized` — the **standing probe**: teach the walker that construct and this must fail loudly, and a new one is chosen |
| `examples/voidcommit` | 2 | the cell the probe opens. 4 states exact, **`VIA_CALLEE`**, `FIELD_MUTATION`, 0/4. At `master`: 0 machines, **0 states** |
| `examples/voidfold` | 3 | **the load-bearing negative control.** Indistinguishable from `voidcommit` at the call site — same hierarchy, driver, method name, arity and argument — differing only in that the callee writes a `String` field. `foreignfold` one indirection deeper. Must never be a machine |
| `examples/unreadablecallee` | 3 | **the F11 control.** An interface method with no implementation in the source set: an unread body is not evidence of a commit. Here the F11 mistake *fabricates* a machine rather than deleting an edge, which is the worse direction |
| `examples/opaquepolymorphic` | 2 | **the second, independently-sourced generality fixture.** The same property at `POLYMORPHIC_OVERRIDE`, with three *different* mechanisms putting the successors out of reach (map lookup, arithmetic index, supplier). Not derived from fixture 1 by copy-editing. Here the source state is exact by construction — the declaring class — so every edge's source is known and no edge's target is: the cleanest statement that the two claims are independent |
| `examples/emptycandidate` | 3 | a candidate whose `permits` clause names a **nested sealed type** and a **permitted enum**, so the channel reports 7 states and not 3. Proves the candidate's state set is the set the machine path would have produced, nesting preserved, rather than a flattened approximation |

Nothing in `examples/` was shaped around the implementation. The one place the
implementation was shaped around a fixture is recorded above: the root-typed
narrowing, which `examples/nestedroots` forced.

### Measured

Corpus, all 40 pre-existing fixture directories:

* **every `.dot` and `.scxml` byte-identical** to `master`. Verified by running
  the CLI over each directory into its own tree before and after and diffing.
* totals unchanged: **46 machines, 599 transitions, 591 resolved**.
* **+12 candidates newly reported**, on hierarchies that were previously rejected
  with no state set: eight event alphabets that are switched over
  (`lcp.LcpEvent`, `http2.StreamEvent`, `dhcpclaude.DhcpEvent`,
  `websocket.WebSocketEvent`, `guardforms.Trigger`, `examples.gofcontext.Event`,
  `examples.eventalphabet.Event`, `ffmpeg...FfmpegEvent`), three documented
  negative controls that genuinely *are* discriminated
  (`examples.foreignfold.Mode`, `chaindispatch.Glyph`, `chaindispatch.Tree`), and
  `nestedroots.Message`. That event alphabets land here is not noise but the
  thesis's own point restated: at the discrimination, a transition switch and a
  fold are identical, and only the commit separates them. `examples/shape`,
  `treebuilder`, `retrystate.Layer`, `nestedroots.Node`, `door.Event` and
  `tcp.Event` correctly yield **no** candidate — the first three because nothing
  discriminates them or they are vetoed, the last two because nothing in their
  model switches on them.
* with the six new fixtures: **49 machines, 610 transitions, 591 resolved,
  15 candidates**. The resolved count is flat by construction — every new machine
  is Tier 2.
* exactly **one** machine in the whole corpus reports `VIA_CALLEE`
  (`voidcommit.Hopper`), asserted directly so the probe cannot start taking credit
  for machines the direct path finds.
* **194 tests green** (183 before, +11), with the pre-existing 183 unchanged.

### The scope boundary, stated as a positive predicate

For a dispatch over a sealed hierarchy H, the tool proves:

* **Commit existence** — that the chosen branch *installs* a value of H — from the
  declared type of the installation site: the host's codomain, the assignment
  target's declared type, a carrier's single H-typed component, a mutator's
  parameter, or (k = 1) a root-typed field write inside one callee body the
  analysis read. This is a **type-level** judgement and it is decided without
  evaluating any expression.
* **Successor identity** — *which* member of H is installed — by intra-procedural
  data flow with an inter-procedural fold bounded at k = 2. This is a
  **value-level** judgement.

The two limits are independent because they read different things. Existence
reads codomains and declared types, which are present whether or not the value
can be traced; identity reads expressions, which can be arbitrarily opaque (a map
lookup, an arithmetic index, a supplier) while the type of the slot they land in
stays perfectly legible. So a machine can be recovered with a proven commit and no
resolved successor at all — Tier 2, which is a positive result and not a failure —
and no relaxation of one limit implies anything about the other.

State enumeration is on neither axis. It is exact by construction, from the
`permits` clause the compiler checks, and it is now reported for every hierarchy
the tool examines: as a machine's state set at Tier 1 and 2, and as a candidate's
at Tier 3.

---

## F27 — the transition table written transposed, and the empty relation that went unmarked

Two defects, found by asking what happens to a hierarchy whose dispatch switches
on the **event** rather than on the state. They are independent and only the
second is a widening.

### The question, and why it was measured before it was answered

Every locus the tool recognises is a spelling of *switch on Q*. A table can be
written the other way round — `switch (frameType) { case GOAWAY -> connState =
new Closed(); … }` — and then nothing discriminates the state anywhere. Such a
hierarchy matched no recognizer, was rejected as "no transition producer found",
and because the Tier 3 candidate channel keys on the same state-discrimination it
reported **no states either**, though its `permits` clause names them exactly.

Whether that gap is worth closing is an empirical claim about how Java is
written, so it was counted rather than argued. `CENSUS.md` records the
measurement in full; `scripts/census/` is the instrument. Over JDK 21 (14,723
files) and 145 library source jars (26,432 files), of **909** state fields whose
type is a closed set:

| written by | count |
|---|---|
| a state-major dispatch | 9 |
| a Σ-major dispatch, directly | 17 |
| a Σ-major dispatch, one call deep | 8 |
| no dispatch at all | 875 |

The shape the tool could not see is the **more common** of the two it was
choosing between, and it occurs in the thesis's own domain: Apache HttpClient 5's
HTTP/2 multiplexer commits `connState` from inside `switch (frameType)`, and its
HTTP/1.1 duplexer from inside `switch (closeMode)`. Both were read by hand, not
just counted.

### (a) Σ-major dispatch releases the state set — candidate channel only

`EventMajorDispatch` is the new recognizer, and it is asked **last**, after every
state-major predicate has declined, so it can only add candidates. It is
deliberately **not** a `DispatchLocus` value: that axis classifies where the STATE
is discriminated, and a Σ-major switch discriminates the input, so giving it a
position there would make it reachable from the walkers.

**It never produces a machine, and that is the load-bearing restriction.** A Tier
2 machine's contract is one unresolved edge per dispatched arm *with a known
source state*. A Σ-major arm establishes no source state at all, so a relation
built from one would be sourced entirely at `<unknown>` — the tiers exist so that
a gap in attribution is not dressed as a result.

Three requirements, each one traceable to a population the census found:

* **the selector is a closed type** (enum or sealed) outside H — an alphabet is
  what makes the arms a table, and `switch (int opcode)` is the shape of every
  parser and bytecode reader in Java. Answered from the *declaration*, never from
  a reference's guessed spelling, so an unreadable import cannot decide it;
* **≥ 2 arms commit** — one committing branch is a special case being handled.
  The same threshold, for the same reason, that a type-test chain applies;
* **the host holds a hierarchy value** — an H-typed parameter or field. This is
  Q × Σ → Q asked directly, and it is what rejects `VectorShape.forBitSize(int)`,
  `JavaKind.fromPrimitiveOrVoidTypeChar(char)` and every other decoder the census
  turned up.

The commit itself is **not** relaxed: a write to an H-typed target, a recognised
mutator call, or the k = 1 `CommitProbe` one callee deep — asked of the shared
recognizers rather than restated, so "what counts as a commit" stays one
definition. A Σ-major switch folding into a `String` therefore yields nothing,
which is `examples/voidfold`'s guarantee restated at this locus.

`examples/eventmajor` holds both spellings on ONE class (committing directly, and
committing one callee deep) so no difference of file or context can stand in, plus
four negative controls sharing the package and the event alphabet so that only the
clause under test can be what the recognizer reacted to: `Mode` folds into a
`String` (and keeps an H-typed field, or it would be excluded by the wrong clause
and test nothing), `Shade` is a factory with no Q in, `Tone` switches on an `int`,
`Beat` commits in exactly one arm.

### (b) An empty relation is a tier, not a bug to be excluded from the tiers

`isDetectedEmpty()` required `!transitions.isEmpty()`, on the reasoning that a
dispatched arm always yields an edge so "no transitions at all is a bug rather
than a tier". The reasoning is right and the conclusion was backwards. A machine
can be accepted on a producer's **signature** — a method taking and returning H —
while nothing in the model discriminates the state; then there is no arm to yield
an edge, the walk reaches no production, and the machine fell through all three
tiers and printed as a clean `0/0`. In a stratified recall table that reads as a
vacuous row rather than as the total loss it is.

An empty relation is the most complete failure of transition recovery there is, so
it is the last thing that may go unmarked. The predicate is now
`resolvedTransitionCount() == 0`, and the report distinguishes the two Tier 2
shapes: *every arm attributed and no target resolved* versus *no arm attributable
at all*. `examples/emptyrelation` is the fixture, and `synchronized` is its
standing probe for the same reason it is `examples/opaquesuccessor`'s.

### Measured

* Corpus **byte-identical**: all 46 pre-existing fixtures, `.dot`, `.scxml` and
  `summary.txt` alike, across both halves. Nothing moved; two fixtures were added.
* 197 tests green (194 before, +3). The property test over *every* directory in
  `examples/` picks the two new fixtures up automatically and asserts their state
  sets against an independently recomputed `permits` closure.
* `examples/eventmajor`: 0 machines, **1 candidate, 4 states, 2 Σ-major sites**;
  the four controls yield neither a machine nor a candidate. At HEAD the whole
  package reported **0 states**.
* `examples/emptyrelation`: 3 states, `0/0` now carrying its tier and its reason.

### Cells

No `(DispatchLocus × CommitForm)` cell changes: a Σ-major dispatch is not a locus
and produces no transitions. What changes is the **reporting** channel — the Tier
3 predicate gains a second, disjoint kind of evidence, and the two are reported
under different sentences because they fail for opposite reasons: a state-major
locus has the discrimination and no commit, a Σ-major one has the commit and no
discrimination.

### The scope line this leaves standing

Apache's `consumeFrame` **is** a real machine, and reporting it as a candidate
understates it. Recovering its relation means attributing a source state to an arm
that never matched one, which requires reasoning about what the state field held
on entry — an inter-procedural, flow-sensitive question the tool does not ask
anywhere. The honest boundary is therefore: the states are exact and named, the
dispatch sites are named, and no relation is claimed. Closing it is future work
and is a genuinely larger change than this one.

---

## F29 — the binding was threaded; the traversal around it was not

### The premise that turned out to be false, and what was underneath it

The task statement for this finding was: *"on entry to a callee, the
inter-procedural fold discards the caller's argument expressions, so every read of
a callee parameter is unresolvable by construction."* **That was true before F25
and is false on `master`.** F25 (`da4f5cb`) already threads an identity-keyed
`CtParameter → CtExpression` map per fold frame, resolves a bound parameter by
resolving the caller's expression one frame out (rule 5), refuses a reassigned or
varargs parameter, and derives F24's "selector parameters" from the map rather
than keeping a second set. The binding itself was not the gap.

What the audit found instead is that the binding sat inside a traversal that
still assumed things the binding existed to establish. Seven places, each a
separate defect, each measured on its own fixture and attributed by ablation:

| # | where | what was assumed | failure direction |
|---|---|---|---|
| 1 | rule 5's cycle guard | `seen` was a `HashSet<CtElement>` — **structural** equality | recall: any chain reusing a parameter name (`x`→`x`, `state`→`state`) stopped at hop 2 |
| 2 | rule 5's reading of the bound expression | the pure resolver, which never folds | recall: `forward(States.armed())` bound `s` to a call and answered "a call — unresolved" |
| 3 | `this` inside a folded callee | always the current state (no receiver slot) | **fabrication**: `new Drive().hold()` from `Park` published `Park → Park` |
| 4 | the body the fold enters | Spoon's STATIC binding is the body that runs | **fabrication** (a virtual call folded off one of two bodies; an overload Spoon chose by guess) **and deletion** (F9 read off the always-throwing body while an override returns) |
| 5 | recursion detection | a signature string, which carries no declaring type | recall: `A.step(E)` → `B.step(E)` read as recursion |
| 6 | a state switch inside a folded callee (F18) | the switched value is the current state | **fabrication + deletion**: `settle(new Blink())` from `Dark` skipped the arm that runs and published `Dark → Dark` |
| 7 | void callees | never entered — the fold only enters callees that RETURN a state | recall: `install(new Launching(), why)`, whose body writes `this.state = next`, stayed "commit proven, successor unknown" |

Rows 1, 3, 4 and 6 are the ones worth dwelling on. Row 1 is an F13 bug that F25
reintroduced one data structure over: F25's own fixture used a distinct parameter
name at every hop (`onRolling(Sig e)` → `wrap(Carry s, …)`), so the defect was
invisible there and would have fired on almost any real forwarding chain. Rows 3,
4 and 6 all failed **in the unsafe direction behind a clean score** — the
fixtures below report 5/5, 2/2 and 3/3 before the fix, with fabricated edges in
each.

### The fix: one frame, and every fold question asked of it

`extract/BindingFrame` replaces F25's two parallel stacks (the extractor's
`Deque<String>` of signatures and the resolver's `List<Map>` of bindings, pushed
and popped together by convention): `(callSite, callee, bindings, receiver,
refusals, caller, depth)`. Two properties of it carry the fix.

* **`caller` is a link, not a stack position.** A bound expression is evaluated in
  the frame it is WRITTEN in. When that expression is itself a call, folding it
  opens a new frame whose caller is that written frame, while the physical walk is
  still inside the callee that read the parameter — an index would name the wrong
  frame. Recursion (`onChain`, by declaration identity) and the depth budget are
  therefore questions about the simulated call chain, not about how deep the
  analysis's own Java stack is.
* **The receiver is a slot of its own**: the call's target as written, `null` for
  a static callee. `this` in a folded body resolves through it, in the caller's
  frame; `Outer.this` and a `this` inside a nested body are not the callee's own
  and are refused.

Around it:

* **Deferred evaluation (row 2).** When rule 5 or the receiver slot meets a bound
  expression the pure resolver cannot read but the extractor can — a call, a
  switch expression, a reassigned local — it hands it back as
  `TransitionResolver.Deferred(expression, frame)`, and `handleValue` evaluates it
  in that frame with the caller's own analysis. The bound expression gets exactly
  the treatment it would have had as a value the caller produced itself; the fold
  claims what the caller would claim had it inlined the callee, and nothing more.
  A deferred candidate is `resolved == false` and carries the unresolved `raw` it
  stands for, so every consumer that does not know about deferral records exactly
  what it did before. Termination: each deferral is keyed on a distinct source
  node (identity set); a re-entered one answers with the old unresolved edge.
* **`seen` is identity-keyed (row 1).** The F13 rule, applied where it was missing.
* **`detect/dispatch/CallTarget` (row 4)** answers "which body does this call
  RUN?" before any body is read — unique by construction (`static`, `private`,
  `final`, a `super.` call), or no method in the model overriding the bound one in
  a type related to the receiver's static type. It also refuses a binding Spoon
  made by guess: under `noClasspath` an argument of unresolved type gives JDT
  nothing to choose an overload with, and Spoon binds the **first same-arity
  declaration** (measured on Spoon 10.4.2 with a throwaway probe before any code
  was written). Asked by the value fold, by the void fold, and by F9 on the
  carrier path — F9 is exact about the body it reads, so it may only read the body
  that runs; a non-unique always-throwing callee is now a recorded gap instead of
  a deleted edge. A shadow is exempt (it is never summarised, F11).
* **Foreign switches (row 6).** `TransitionResolver.selectorCurrentness` asks the
  frames whether a state switch's selector — a folded parameter or `this` —
  holds the current state. Only a definite **no** changes anything: the switch is
  walked from the caller's from-state, arms the binding excludes are skipped, and
  an arm not certain to match carries its type test as the guard
  (`b instanceof Dark`). "Unknown" (a refused binding, a field, a local) leaves
  F18's reading exactly as it was. A root-typed pattern binding of such a switch
  is that other value, so rule 4's pattern-binding exemption does not cover it.
* **The void fold (row 7).** At an arm the k = 1 probe proved commits, the fold
  now enters the callee with the arm's bindings and reads the probe's own commit
  clause (`CommitProbe.isRootFieldWrite`, made public so there is one notion of it)
  as the production. **The probe is not made a resolver** — it still decides the
  tier, on declared types, at depth 1; the fold decides the successor, on
  expressions, within the k-budget every fold spends. A returned value in a void
  fold is discarded (JLS §14.8); a nested committing call is folded in turn or
  marks the fold incomplete, which keeps the probe's marker beside the resolved
  edges; an owned root-field write the walk did not reach is recorded unresolved
  (the F18 accounting). **When the fold resolves nothing, the probe's marker stands
  byte-for-byte** — it says more than a list of unreadable writes, and it is what
  keeps `examples/voidcommit` identical.
* **The deferred-execution boundary** is now stated, not emergent: a parameter
  read binds only when its nearest enclosing executable IS the frame's callee, so
  a read inside a lambda or a local/anonymous class (which may run after the call
  returns) is refused. Before F29 this held because the walker never descends a
  lambda and the lookup only consulted the innermost frame; the fixture pins it on
  the shape where that alone does not say why — a local class whose method IS
  folded.
* **Varargs**: bound only when the call hands the varargs slot one explicit array
  (JLS §15.12.4.2). No resolver rule reads an array element, so this is
  unobservable today; it makes the frame exact rather than conservative.
* **`--explain`** now also prints, per machine, each binding chain a resolved
  value travelled (outermost call first; one hop per call: parameter `:=` caller
  expression `at` call site `[File:line]`) and the rule that stopped each value
  that did not resolve — `DEPTH_EXCEEDED`, `RECURSION`, `OVERRIDDEN`,
  `AMBIGUOUS_OVERLOAD`, `UNRESOLVED_RECEIVER`, `NO_BODY`, `LIBRARY`,
  `REASSIGNED_PARAMETER`, `VARARGS`, `UNBOUND`, `DEFERRED_EXECUTION`,
  `NOTHING_BOUND`. That is what separates a capability gap from a scope limit;
  both are the same dashed edge in the output. A side channel: the output files
  are identical with it on or off (tested).

### Parameter binding belongs to the traversal, not to a cell

None of the seven changes names a `CommitForm`, a `SuccessorForm` or a
`DispatchLocus`. The frame is opened by the fold and read by the resolver, and the
resolver is the single successor sub-procedure every cell already goes through, so
every `(DispatchLocus × CommitForm)` cell whose successor or committed value
crosses a call boundary gets the binding from one mechanism. The two generality
fixtures below were chosen to differ on every axis the task names precisely so
that a cell-specific fix could not pass both.

### Fixtures

`src/test/resources/bindingframes/` — ten hierarchies in one package, all compiled
by `javac`, one question each; `src/test/resources/callambiguity/` — the overload
guess, which by construction does not compile (the `unreadmember` precedent).

| hierarchy | question | before F29 | after |
|---|---|---|---|
| `Bay` | **Positive A** — one hop into a VOID callee with two parameters (so not a mutator); control arm commits a value the callee computes | 0/3, Tier 2 | **2/3**, control keeps the probe marker verbatim |
| `Valve` | **Positive B** — CARRIER_RETURN, **two** hops, successor from a **static factory** | 0/3 | **3/3**, `CONSTRUCTION` only |
| `Gear` | **Positive C** — `this` through the receiver slot: constructed receiver, factory receiver, explicit and implicit `this`, unreadable receiver | 5/5, **every edge `SELF`** | **4/5**, the gap is the unreadable receiver |
| `Lamp` | **Negative C** (3 hops vs a budget of 2; control at 2 hops) and **Negative A** (reassigned parameter) | 0/3 | **1/3** |
| `Pump` | **Negative B** — virtual call, two bodies; control: the same method through a final receiver type; F9 on a virtual callee | 2/2 (one resolved off one of two bodies, one **deleted**) | **1/3** |
| `Latch` | **Negative D** — read inside a stored lambda; inside a kept local class whose method IS folded; control in the callee's own body | 1/3 | 1/3, now explicit and explained |
| `Tide` | recursion by identity — two different `step(Tide)` | 0/2 | **2/2** |
| `Beacon` | a switch in a folded callee over a parameter bound to a construction / the current state / an unreadable call | 3/3, **`Dark → Dark` fabricated** | **5/5**: `Dark → Steady`; control unchanged; three guarded arms |
| `Winch` | F9 on the CARRIER path: the same always-throwing method through a virtual receiver and through a final one | 1/1 (the virtual arm **deleted**) | **1/2**; the unique arm still contributes no edge |
| `Knob` (`callambiguity`) | an overload chosen by guess under `noClasspath`; control with no overload | 2/3, **`Low → High` fabricated** | **1/3** |

**On "independently sourced".** Positive B differs from A on every named axis
(commit form, hop count, how the successor is produced), but both were written in
this session. The F25 entry's standing request — a second generality fixture taken
from real code rather than invented alongside the fix — is still open, and the
holdout corpus was deliberately not consulted for it.

### Attribution, by ablation

Each sub-fix switched off alone, full rebuild, fixture re-run. "none" is the
complete fix; "baseline" is `master` before F29.

| ablated | Bay | Beacon | Gear | Lamp | Latch | Pump | Tide | Valve | Winch | Knob |
|---|---|---|---|---|---|---|---|---|---|---|
| baseline (pre-F29) | 0/3 | 3/3 | 5/5 | 0/3 | 1/3 | 2/2 | 0/2 | 0/3 | 1/1 | 2/3 |
| **none** | **2/3** | **5/5** | **4/5** | **1/3** | **1/3** | **1/3** | **2/2** | **3/3** | **1/2** | **1/3** |
| identity `seen` | 2/3 | 5/5 | 4/5 | 0/3 | 1/3 | 1/3 | 0/2 | 0/3 | 1/2 | 1/3 |
| deferral | 2/3 | 5/5 | 3/5 | 1/3 | 1/3 | 1/3 | 2/2 | 0/3 | 1/2 | 1/3 |
| recursion by identity | 2/3 | 5/5 | 4/5 | 1/3 | 1/3 | 1/3 | 0/2 | 3/3 | 1/2 | 1/3 |
| receiver slot | 2/3 | 5/5 | 5/5 | 1/3 | 1/3 | 1/3 | 2/2 | 3/3 | 1/2 | 1/3 |
| `CallTarget` | 2/3 | 5/5 | 4/5 | 1/3 | 1/3 | 2/2 | 2/2 | 3/3 | 1/1 | 2/3 |
| foreign switch | 2/3 | 3/3 | 4/5 | 1/3 | 1/3 | 1/3 | 2/2 | 3/3 | 1/2 | 1/3 |
| void fold | 0/3 | 5/5 | 4/5 | 1/3 | 1/3 | 1/3 | 2/2 | 3/3 | 1/2 | 1/3 |

Every sub-fix moves at least one fixture, and every fixture that moved is moved by
a sub-fix it was written for. `Valve` needs both the identity `seen` and deferral,
and `Tide` both the identity `seen` and identity recursion — each is a chain, so
each crosses two of the defects at once. `Latch` moves under none, as stated
above: the boundary was already held implicitly, and F29 makes it explicit and
explainable. (The ablation rows are the score only; `Gear`'s ablated 5/5 and
`Beacon`'s ablated 3/3 are the fabrications described above.)

### Regression gate

* Corpus (`scripts/capture-golden.sh`, all **49** fixture directories): **every
  `.dot`, `.scxml` and `summary.txt` byte-identical** to `master` — no state added
  or removed, no edge added or removed, no edge moved in either direction.
  Checked after each sub-fix landed and again on the final build. None of the
  seven rules fires on the corpus, which is itself a finding: the corpus's
  forwarding chains all rename their parameters per hop, none folds a virtual or
  receiver-bearing call, none re-switches on a foreign state, and its one
  VIA_CALLEE machine commits an array element.
* All 199 pre-existing tests green, plus `InterproceduralBindingTest` (14): 213.
* Determinism: DOT and SCXML byte-identical across two runs from two independently
  built models, on both binding fixtures and on `lcp_automation` /
  `lcp_automation_chatgpt` (tested).

### Depth sweep

`scripts/depth-sweep.sh` (new) runs the analyzer over every `examples/*` directory
plus both binding fixtures, each directory its own model, at a budget set by
`-Dsealfsm.maxInterprocDepth=k` — one JVM per k, the property used for nothing
else, so the sweep is re-runnable rather than a hand edit of the constant.
`corpus/` is never passed. Final build, each k run twice: the counts were
identical across repetitions, and the in-process analysis time (model building
excluded) was **9.4–11.0 s at every k with no trend** — at this corpus's size the
budget costs nothing measurable.

| axis cell (Encoding / CommitForm) | machines | k=1 res/total | k=2 | k=3 | k=4 |
|---|---|---|---|---|---|
| CENTRALIZED_DISPATCH / CARRIER_RETURN | 5 | 119/122 | 121/122 | 121/122 | 121/122 |
| CENTRALIZED_DISPATCH / FIELD_MUTATION | 7 | 43/48 | 43/48 | 43/48 | 43/48 |
| CENTRALIZED_DISPATCH / FIELD_MUTATION+MUTATOR_ARGUMENT | 1 | 1/2 | 1/2 | 1/2 | 1/2 |
| CENTRALIZED_DISPATCH / FIELD_MUTATION+VALUE_RETURN | 1 | 6/6 | 6/6 | 6/6 | 6/6 |
| CENTRALIZED_DISPATCH / LOCAL_ACCUMULATOR | 1 | 4/5 | 4/5 | 6/6 | 6/6 |
| CENTRALIZED_DISPATCH / MUTATOR_ARGUMENT | 3 | 12/12 | 12/12 | 12/12 | 12/12 |
| CENTRALIZED_DISPATCH / VALUE_RETURN | 36 | 345/367 | 348/367 | 350/367 | 350/367 |
| MIXED / FIELD_MUTATION | 1 | 3/3 | 3/3 | 3/3 | 3/3 |
| MIXED / MUTATOR_ARGUMENT | 1 | 5/5 | 5/5 | 5/5 | 5/5 |
| MIXED / VALUE_RETURN | 2 | 8/8 | 8/8 | 8/8 | 8/8 |
| POLYMORPHIC / POLY_CARRIER | 4 | 63/64 | 63/64 | 63/64 | 63/64 |
| POLYMORPHIC / VALUE_RETURN | 6 | 23/28 | 23/28 | 23/28 | 23/28 |
| **TOTAL** | **68** | **632/670** | **637/670** | **641/671** | **641/671** |

What it justifies, and what it does not:

* **Monotone non-decreasing in every cell, and flat past k = 3** — the property F25
  restored still holds now that deferred evaluations and void folds spend the same
  budget as ordinary folds. More budget never licenses fewer proofs.
* **It does not, on these numbers, justify k = 2 over k = 3.** k = 3 resolves four
  more edges at no measured time cost, and exactly three machines move between the
  two: `accumulator.Phase` 4/5 → 6/6 (its documented "one out-of-budget
  delegation" resolves, into two edges — the one transition-count change in the
  sweep), `factory.Bolt` 3/4 → 4/4, and `bindingframes.Lamp` 1/3 → 2/3 (Negative
  C, by construction). What k = 3 would cost is precision, and that cannot be
  measured without ground truth. Raising the default also changes two corpus
  fixtures' documented numbers and adds an edge — exactly what this finding's
  regression gate forbade — so the default is **left at 2** and the choice is
  recorded as a decision to take separately, with this table as its evidence.

## F30 — a static method runs in no state

Found by running the tool over Apache Kafka's `raft` module (`4.5.0-SNAPSHOT`):
`org.apache.kafka.raft.internals.KRaftVersionUpgrade`, a sealed interface its own
javadoc calls a sum type, was published as a POLYMORPHIC machine, `1/1`, whose one
edge ran from the root interface to `Empty`.

### The defect

`findDistributedTransitionMethods` admitted every method declared on a hierarchy
member whose return type is in H, and never asked whether the method has a
receiver. What makes a per-state method's declaring class an exact source state is
that the receiver's dynamic type selects the body. A `static` method has no
receiver, so it runs in no state. Its only H-returning method,
`static KRaftVersionUpgrade empty() { return EMPTY; }`, was therefore walked as
if it ran in every state, and `sourceStatesOf` named the root, which is not a
state. The carrier path had always excluded statics (`CarrierTransitionDetector.
shapeOf`, `nestedProductions`), so the two routes to the same locus disagreed.

It is the same root cause as F28's `Codec.forId(int)`. F28 closed that instance by
making enums never roots, which removed the symptom for enums and left the
admission itself in place for every sealed class and interface. Static factories
(`of`, `empty`, `parse`, `create`) are ordinary on sealed result and option types,
so the admission is common on real code, not exotic.

It failed in three directions, and `examples/staticfactory` holds one hierarchy
per direction (every file compiled by `javac`):

| hierarchy | shape | before | after |
|---|---|---|---|
| `Upgrade` | the Kafka shape: a sum type whose only H-returning method is a static factory on the root | **machine**, 1/1, edge sourced at the root | rejected |
| `Lamp` | a real POLYMORPHIC machine carrying a static factory on the root, a static factory on a leaf (`Off.create()`), and a private static helper reached from `On.toggle()` | **4/4**: `Lamp -> Off` sourced at the root, `Off -> Off` a fabricated resolved self-loop indistinguishable from a real one, and every edge labelled with a method name because the statics made the names look like they discriminate (F22) | 2/2, no labels, the helper still folded |
| `Gauge` | a centralized transition function **declared on the root**, `static Gauge next(Gauge, Tick)`, dispatching with a switch *statement* whose arms return | Tier 2 **total loss** (0/0), filed as POLYMORPHIC | CENTRALIZED_DISPATCH, **6/6** |

`Off.create()` is the sharp case. The Kafka instance is visible in the
diagram, because a root is drawn as a red pseudo-node. A static factory on a
**leaf** sources its edge at a real state, and nothing in the output gives it away.

### The fix: statics are never per-state, and are offered to the centralized recognizer

Two lines, in the two recognizers that partition the methods declared in H:

* `findDistributedTransitionMethods` skips `static` methods.
* `findCentralizedTransitionMethods` skipped every method declared inside H, on
  the stated ground that the distributed recognizer had already counted it. That
  ground no longer covers a static method, so the skip now applies to instance
  methods only. A static method on H is then asked the question every centralized
  candidate is asked: does it take the state as an argument, or discriminate it?
  `empty()` and `create()` do neither and are rejected. `Gauge.next` takes the
  state and is accepted.

A plain drop would not have been enough, and `Gauge` is the control that proves it.
Its dispatch is a switch statement, which `DispatchCommitDetector` cannot see,
so the signature-based centralized recognizer is its only route. The
re-offer is what keeps it.

**For the hierarchy whose statics are in question, the fix can only withdraw an
acceptance, never grant one.** Every method the centralized recognizer now
admits was, before, admitted by the distributed recognizer, since it is declared
in H and returns H. What changes for such a method is how it is walked. The walk
moves from a per-state walk sourced at the declaring type to a centralized walk
that reads the source from the discrimination. There are two indirect
consequences, both the designed behaviour of existing mechanisms and neither
exercised by the corpus. A root that now abstains may appear on the candidate
channel (F26). It also re-offers its nested sealed subtypes as roots, and one of
them may then be a machine in its own right (the re-offer worklist). Both only
report what a static factory used to hide.

### Attribution, by ablation

| ablated | `Upgrade` | `Lamp` | `Gauge` |
|---|---|---|---|
| baseline (both halves off) | machine 1/1, root-sourced | 4/4, two fabricated | Tier 2, 0/0 |
| **none** | **rejected** | **2/2** | **6/6, CENTRALIZED** |
| centralized re-offer only | rejected | 2/2 | **Tier 3 candidate** — the machine is lost |

With the re-offer ablated, `Gauge`'s candidate reason reads "no branch installs a
hierarchy value … the exhaustive-fold guard", which is false for a switch whose
arms return H. That wording is the separate open issue recorded in
`LIMITATIONS.md` (one candidate sentence for two different gaps), not part of this
finding.

### Regression gate

* Corpus (`scripts/capture-golden.sh`, all **49** pre-existing fixture
  directories): every `.dot`, `.scxml` and `summary.txt` **byte-identical**. The only
  difference is the new `staticfactory` directory. No corpus fixture declares a
  static H-returning method on a hierarchy member that the distributed walk
  reached. The corpus's static transition functions (`DoorMachine.transition`,
  `StreamStateMachine.next`, …) all live in driver classes outside H.
* Kafka `raft`: `KRaftVersionUpgrade` is now rejected, and `EpochState` /
  `NomineeState` are unchanged Tier 3 candidates.
* Test: `ExtractionIntegrationTest.aStaticMethodOnTheHierarchyRunsInNoState`,
  asserting all three hierarchies edge by edge, that no machine has an edge sourced
  at its own root, and that the two recognizers agree on why (no static method
  among the distributed ones, `Gauge.next` among the centralized ones).

### What it leaves standing

* **Initial-state rule 1 reads a constant on the root as a seed, by design.**
  `KRaftVersionUpgrade EMPTY = new Empty();` seeded Kafka's (then-accepted)
  machine with `Empty`, which is the correct answer: `LeaderState` starts its
  `AtomicReference` from `empty()`. `isSelfSingleton`'s javadoc states the decision
  explicitly: a root constant names a start state, and only a field constructing its
  own declaring type is excluded. No corpus fixture declares a root constant, so
  the decision is unpinned in both directions. Its weakness is that the evidence
  is a shared constant rather than a driver's current-state field, and the output
  does not say so.
* A static method on H that takes the state is now a centralized function **by
  the same rule as one declared outside H**, including its known looseness: F19
  admits a method with an H-typed parameter and no discrimination
  (`factory.shutOnTurn`). Nothing new is admitted by that route that was not
  admitted before (see above), but it is where a future false positive of this
  shape would come from.

## F31 — an initial state seeded only by a constant is reported as the weaker evidence it is

Found on the same Kafka run as F30. `KRaftVersionUpgrade EMPTY = new Empty();`,
a constant declared on the sealed root, was the only `new` that seeded the
machine, and initial-state rule 1 reported `Empty` exactly as it reports a
driver's state field (`private TrafficLight current = new Red();`), with nothing
in the output to tell the two apart.

### Not a restriction, and why

My first recommendation was to ignore such constants, as rule 3 ignores locals
declared inside the hierarchy. That was wrong, for three reasons:

* **The code already decided it, on purpose.** `isSelfSingleton`'s javadoc says a
  constant on the root (`Signal START = new Idle();`) names a start state. Only a
  field constructing its OWN declaring type (a flyweight, an enum constant) is
  excluded.
* **On the one real instance it was right.** `LeaderState` starts its
  `AtomicReference` from `KRaftVersionUpgrade.empty()`, which returns `EMPTY`.
* **The two possible errors differ in direction.** Ignoring the constant turns that
  right answer into a gap. Trusting it silently gives a wrong answer in the case
  below, with nothing to warn the reader.

So the verdict is unchanged, and the report changes. When rule 1's unanimous
answer rests on constants declared inside the hierarchy ALONE, with no field
outside it seeded the same way, the answer stands and carries the weaker-evidence
line rule 3 already prints: "initial state 'Shut' inferred from a constant
declared on the hierarchy itself (Valve.SHUT = new Shut()), not a driver's state
field — weaker evidence …". "Inside the hierarchy" is the same
`declaredInsideHierarchy` predicate rule 3 uses.

### Fixture: `examples/rootseed`

Four two-state POLYMORPHIC machines, identical except for what seeds them (every
file compiled by `javac`):

| hierarchy | seeds | before | after |
|---|---|---|---|
| `Valve` | root constant only; the driver starts from it (`Valve.SHUT`) | `Shut`, unlabelled | `Shut`, **labelled** |
| `Gate` | root constant + a driver field `new Closed()` that agrees | `Closed` | `Closed`, **not** labelled (the control) |
| `Lamp` | root constant `Off` + a driver field `new On()` that disagrees | no initial state, disagreement reported | unchanged: unanimity holds in both directions |
| `Fan` | root constant `Idle`; the driver starts in `Spinning` through a static factory rule 1 cannot see | `Idle`, **wrong**, unlabelled | `Idle`, still wrong, **labelled** |

`Fan` is the documented cost and a standing probe. It pins a wrong answer on
purpose, because that is the rule's real behaviour and the label is what makes it
honest. If rule 1 ever learns to follow a factory into the driver's field, its
test fails and should be updated to the new answer.

**Ablation.** Label every answer a constant contributed to, instead of only those
no driver vouches for: `Gate` is labelled too, and its label becomes false ("not a
driver's state field" while a driver field exists).

### Regression gate

* Corpus: all **50** pre-existing fixture directories byte-identical (`.dot`,
  `.scxml`, `summary.txt`). The only difference is the new `rootseed`. No corpus
  fixture declares a hierarchy-typed constant constructing another member
  (`valueforms`' and `rivalseeds`' `INSTANCE` constants all construct their own
  declaring type, and `isSelfSingleton` already excludes them).
* Test: `ExtractionIntegrationTest.anInitialStateSeededOnlyByARootConstantIsReportedAsWeakerEvidence`.

## F32 — a candidate says which evidence was missing, site by site

Found on the same Kafka run. `EpochState`'s candidate named five discrimination
sites under one sentence, "no branch installs a hierarchy value, so no commit is
proven (the exhaustive-fold guard …)". That was true of three sites
(`QuorumState.maybeXState()`, which fold into `Optional`) and false of two
(`KafkaRaftClient.maybeTransitionForward` / `maybeHandleElectionLoss`, real
transition dispatches whose arms are calls committing five calls away). The
`--explain` trace printed the same sentence for the same decision.

### Why a wrong sentence matters here

It is the tool's own explanation of a non-result, and `CANDIDATES.md` sorts the
candidate channel by it. Filing a depth-bounded dispatch under the fold sentence
makes a **recall** limit read as the **precision** guard working, which is the
one confusion the tier design exists to prevent. The comment above the sentence
already required a candidate to say which half of the evidence was missing. It
did so between state-major and Σ-major dispatch (F27), but not within
state-major.

### Four situations, not one

Measured on the corpus, the old sentence covered four distinct gaps:

| sub-kind | what the branches do | corpus instance |
|---|---|---|
| **fold → T** | produce values of a type T outside H | the event alphabets (→ their machine's state type), `foreignfold`, `emptycandidate`, `Glyph` |
| **composition** | install H values that nest another H value, and the veto rejected them | `chaindispatch.Tree`, where "no branch installs a hierarchy value" was false |
| **calls** | call methods, and no body read one call deep writes a root-typed field | `voidfold`, `nestedroots.Message`, `gofcontext.Event`, and the new `deepcommit` |
| **unreadable calls** | call methods whose bodies were not read (F11) | `unreadablecallee` |

Two more kinds exist for completeness and are not reached by the corpus. The
branches may produce H values in a position no commit rule reads (seen only
with F30's re-offer ablated), or they may do none of the above.

### The fix

`detect/dispatch/CommitGap` classifies each site into exactly one kind, in a
fixed precedence: what the branches produce comes before what they call, and
calls come before bookkeeping writes. `summarize` groups the sites by kind and
names each group's hosts. `Analyzer.recordCandidate` and
`StateMachineClassifier`'s `--explain` line both print that summary, so the two
reports of one decision cannot disagree.

* Composition asks the veto's own predicate (`CompositionVeto.nestsHierarchyValue`),
  so the explanation cannot contradict the rule that rejected the dispatch.
* "calls" requires the probe to have actually read a body. `CommitProbe.Result`
  gained `probed`, the callees whose bodies were read and scanned, so the candidate
  says it looked one call deep only if it did.
* The "calls" sentence never claims absence: "no method body read one call deep
  writes a field of the hierarchy's root type (a commit deeper than one call is
  possible and is not claimed)". Of the four corpus candidates with a "calls"
  site, one is a real machine (`deepcommit`) and three are not.

**Text only.** `CommitGap` is consulted by no verdict. It runs after the
classifier has decided that no commit was proven, and a wrong kind is a wrong
sentence, never a wrong machine. That is why it may read the arms more coarsely
than the walkers do.

### Fixture: `examples/deepcommit`

Kafka's shape in miniature, on one class. `Kiln.tick()` is a real transition
table (Cold → Warm → Hot → Cold) whose arms call helpers that call a mutator, so
each commit is two calls deep. `Kiln.describe()` is the **negative control**: the
same discrimination folded into `String`, which must still get the fold sentence.
Before F32 both got one sentence. After, the reason reads "fold → String
[Kiln.describe]; calls [Kiln.tick]". The hierarchy stays a Tier 3 candidate,
correctly, because the probe's depth is 1 by design.

### Regression gate

* Corpus: every `.dot` and `.scxml` **byte-identical** across all 51 pre-existing
  fixture directories. `summary.txt` changed in exactly the **14** directories
  that produce a candidate, only in candidate reason text, and each new reason
  was read against its fixture (table above). New: `deepcommit`.
* Kafka `raft`: `EpochState`'s reason now reads "3 fold → Optional
  [QuorumState.maybe…]; 2 calls [KafkaRaftClient.maybeTransitionForward,
  maybeHandleElectionLoss]". `NomineeState`'s reads "2 calls".
* `CANDIDATES.md` re-enumerated from the new capture: 17 candidates, with the kind
  column now at sub-kind granularity. **Two** real machines are among them:
  `eventmajor.Link` (Σ-major) and `deepcommit.Phase` (depth), where there was one.
* Test: `StateCompletenessTest.aCandidateSaysWhyEachSiteProvesNoCommit`, covering
  deepcommit's two groups and one existing control per other kind.

## F33 — the GoF State pattern commits through a context, and nothing recognised it

**Defect.** The textbook State pattern (a `void` method per input on the state
interface, rejected inputs spelled as throwing `default` bodies or as throwing
overrides, accepted inputs installing the successor into a context with
`order.changeState(new PaidState())` or `desk.state = new Closed()`) was
**rejected with no states reported** unless the root carried an `@Fsm` marker.
Every recognizer keyed the `POLYMORPHIC_OVERRIDE` locus on the codomain ("returns
H" or "returns a carrier of H"), so a `void` per-state method matched nothing, and
the F2 fallback that could read it runs only behind the marker, with no locus. The
table above marked `POLYMORPHIC_OVERRIDE × {FIELD_MUTATION, MUTATOR_ARGUMENT}` as
`R*` for that reason. Even with the marker, edges carried no event (so a
deterministic machine drew nondeterminism warnings) and the encoding read `MIXED`.

**Fix.** New `detect/ContextCommitDetector`, a sixth `DispatchFinder` route
(`contextCommits`), asked by the classifier after the carrier check so it can only
add machines. A context commit is a statement in a member method's own body that
installs an H value into a holder outside H. It is either a `MutatorRecognizer`
mutator declared outside H, or a `CommitProbe.isRootFieldWrite` whose field is
declared outside H. The hierarchy qualifies on the carrier path's threshold:
committing methods on ≥2 members, and ≥1 production naming another state
(`CarrierTransitionDetector.targetQualifiedName`, now package-visible and shared).
The extractor walks each such method at `sourceStatesOf` (so a committing root
`default` is sourced at exactly the leaves that inherit it, F28). Its own `return`s
are not successors. Its names join the F22 discrimination question.

**Throwing cells.** A `throw` contributes no edge, as it always has (D3). It is not
an unresolved edge, because it provably installs nothing. For every signature
some state commits through, each hierarchy body whose last top-level statement is
a `throw` and which commits nothing is a *rejecting cell*. It marks its states as
examined (so an all-rejecting state is terminal rather than unreached) and is
counted in a diagnostic. A body that neither commits nor rejects is **not**
counted, since its successor might be one call away and calling it examined would
dress a recall gap as an absorbing state.

**Fixture** `examples/gofstate`, four hierarchies in one package:

| hierarchy | role | HEAD | F33 |
|---|---|---|---|
| `OrderState` | positive: throwing defaults, mutator commit | rejected, 0 states | 6 states, 6/6, labelled, 3 terminal, 24 rejecting cells |
| `TicketState` | positive: abstract + throwing overrides, field-write commit, guarded commit, `boolean` return | rejected | 3 states, 3/3, `FIELD_MUTATION`, guard `desk.resolved()` |
| `Node` | control: a tree writing its own slot | rejected | rejected |
| `Message` | control: an echo installing `this` | rejected | rejected |

**Ablations (run, not argued).** Drop "holder outside H": `Node` published, 3/3.
Drop "names another state": `Message` published as two self-loops, 2/2. Drop the
own-return suppression: `TicketState` 3/4, with `return true` as a fabricated
unresolved edge. Drop the rejecting-cell accounting: no terminal state in either
machine.

**Corpus.** Byte-identical except `gofcontext.Portal`: `MIXED` → `POLYMORPHIC`,
relation unchanged at 5/5, plus the accurate F22 "one name names the function"
diagnostic. That closes Portal's half of the open `MIXED` item. `Vend` is not
affected. **Real code:** the Kafka modules with sealed types (`raft`, `server`,
`storage`, `transaction-coordinator`, `group-coordinator-api`) give byte-identical
output before and after, so there are no new acceptances. That is evidence about
precision only, since none of those types is GoF-style.

**Residual risk, stated.** A sealed *message/command* hierarchy whose handlers post
a *different* member into a holder outside H (`Ping.reply(bus) { bus.publish(new
Pong()); }` on two members) meets every clause and would be published. Structurally
it is indistinguishable from the State pattern; the echo control rejects only
the case where every production is `this`.

**Cell table update.** `POLYMORPHIC_OVERRIDE × FIELD_MUTATION` and
`× MUTATOR_ARGUMENT` go from `R*` to **R**: `examples/gofstate`, and
`gofcontext.Portal` without relying on its marker.

## F36 — a returned successor is a transition only where a caller installs it (thesis Decision 4)

**Defect.** A value-returning host (a per-state `next()`, a centralized
`transition(H, E)`, a typed handler, a functional callable, a carrier) was
committed by its **codomain alone**: returning H *was* the `VALUE_RETURN` commit.
A conversion within a sum type has the same codomain and the same body shape, so
a family of converters was published as a machine. `LIMITATIONS.md` L3 recorded
this and pinned `typedhandler.Length` (`toFeet(Meters)` + `toMeters(Feet)`) as
a wrong answer on purpose. F35's family threshold removed only the
one-converter case, and it cost a real one-handler machine (`typedhandler.Lamp`).
Thesis Decision 4 ("converters are not FSMs") requires the fix L3 specified,
applied at every locus, with missing caller evidence read as uncertainty.

**Fix.** New `detect/dispatch/Installation`. For every site whose successor
leaves its host by `return` (`VALUE_RETURN`, `CARRIER_RETURN`, `POLY_CARRIER`,
and a `LOCAL_ACCUMULATOR` that is returned), it follows the returned value to
where it goes. The value passes through conditionals, switch-expression arms,
locals (flow-insensitively), returns (to the host's callers, including callers
of a method the host overrides) and arguments (into a unique in-model callee's
parameter), for up to `MAX_DEPTH` = 6 call boundaries. It stops at one of four
sinks:

1. a write to a field declared with the root (`CommitProbe.isRootFieldWrite`, the
   probe's own clause);
2. a write back into the variable the call read its state from (`s = step(s)`,
   `current = current.next()`);
3. the argument of a recognised mutator (`MutatorRecognizer`);
4. the selector argument of a run-to-completion driver (`RunToCompletion`, F34's
   predicate, now shared with the extractor rather than private to it).

A carrier is followed until its hierarchy-typed component is read out
(`t.next()`, `opt.orElse(h)`). Each site gets one verdict:

| verdict | meaning | established? |
|---|---|---|
| `IN_HOST` | installed inside the site: field mutation, mutator, probed callee, context commit, a stored accumulator, or a stateful driver's own dispatch (its return is a read-back, F19) | yes |
| `REENTRY` | a run-to-completion driver | yes |
| `INSTALLED` | a caller stores it back (`CommitEvidence.VIA_CALLER`) | yes |
| `NO_CALLER` | no call exists in the source set | no, uncertain |
| `OPAQUE` | a caller hands it somewhere not followed (library, collection, lambda, non-unique target, budget) | no, uncertain |
| `CONVERTED` | callers exist and every one uses it as data | no, **established conversion** |

The classifier accepts on established sites only, and the extractor walks
exactly those sites. The report travels with the `Classification`, so the two
cannot disagree. Outcomes:

- some site established: a **machine**. Unestablished sites are not walked, and
  a diagnostic names them;
- every unestablished site `CONVERTED`: `Rejection.CONVERTED`. This releases no
  candidate (Decision 4 forbids publishing a known conversion on either channel)
  and still re-offers nested roots;
- otherwise: an **abstention**. It is a provisional candidate with basis
  `INSTALLATION_UNSHOWN` when the sites amount to a dispatch (a discrimination,
  or per-state sites fixing ≥2 source states; F35's threshold, now a
  plausibility test) and a plain abstention otherwise.

`findCentralizedTransitionMethods` admits every typed handler: F35's acceptance
threshold is retired, and installation decides. `CommitEvidence` gains
`VIA_CALLER` and `weaker()`. The DOT/SCXML evidence comment stays reserved for
the probe's `VIA_CALLEE`, which qualifies edges rather than the classification.

**Fixtures.**

| fixture | role | before | after |
|---|---|---|---|
| `typedhandler.Length` | Decision 4's named example: two converters, no caller | machine 2/2 (pinned wrong) | provisional candidate |
| `typedhandler.Lamp` + `LampPanel` | demonstrated state update, one handler | rejected (F35 cost) | machine 1/1, `VIA_CALLER` |
| `typedhandler.Temperature` (new) | converter use: results switched over into a `String` | — | rejected as a conversion, no candidate |
| `typedhandler.Shape` | one uncalled converter | rejected | abstained, no candidate |
| `typedhandler.OrderState` | a pipeline without its driver | machine 5/5 | provisional candidate (missing caller) |
| `converters.Tint` (new) | switch converter, stored back | — | machine 2/2 |
| `converters.Shade` (new) | the same, result used as data | — | rejected as a conversion |
| `converters.Hue` (new) | the same, never called | — | provisional candidate |
| `converters.Currency` (new) | per-state converter, used as data | — | rejected as a conversion |
| `functionaldriver` (new) | `cancellation`'s callables, installed in-model | — | machine 6/6, identical relation |
| `cancellation` | installs through `AtomicReference.accumulateAndGet` (L2) | machine 6/6 | provisional candidate |
| `nestedroots.Rebuilder` (new) | `branch = branch.replaceChild(child)`: a persistent-tree update is a store-back | — | keeps the veto control meaningful |

**Re-baselining, measured.** 22 corpus machines had no installing caller,
because their fixtures were written without a driver (the `@Fsm`-marked `Vend`
needs no evidence and is not counted). The 19 whose purpose is extraction, in
14 directories, received a minimal, **unseeded** store-back class, so the initial-state heuristics
are handed nothing new: `enumbodies.Panel`, `errorhandling.JobDriver`,
`eventalphabet.PlayerDriver`, `factory.BoltDriver`, `guardforms.SignalDriver`,
`localvar.GateDriver`, `nonreturning.LatchDriver`, `opaquesuccessor.LatchDriver`,
`opaquepolymorphic.RotorDriver`, `plumbing.ConveyorBelt`,
`staticfactory.Console`, `turnstile.TurnstileDriver`, `valueforms.SignalDriver`,
`typedhandler.HatchPanel`, and `press`/`run` on the two F19 stateful drivers. The
test resources received `Installers`/`KnobPanel`/`Lantern` for the same reason.
Every `.dot` and `.scxml` of every re-driven fixture is **byte-identical** to
`master`. That is the check that the drivers added evidence and changed nothing
else.

**Regression gate.** 57 fixtures. DOT and SCXML are byte-identical to `master`
except the intended changes (cancellation out, `Length`/`OrderState` out, `Lamp`
in, two new fixtures) and F37's SCXML header counts. 67 machines before and after
(16 `DIRECT`, 50 `VIA_CALLER`, 1 `VIA_CALLEE`). Candidates 17 → 21, all four new
ones `INSTALLATION_UNSHOWN` (`CANDIDATES.md`). Tests: `InstallationEvidenceTest`,
plus updated `ExtractionIntegrationTest` and `StateCompletenessTest`.

**Residuals, stated.** A converted value cached in a root-typed field reads as
installed (the decision's own clause, "an assignment to a state field"). Local
flow is flow-insensitive, which errs toward installation. A library container
(L2) and a store-back outside the source set both read as uncertain: a real
machine whose driver lives in client code abstains, and the evaluation protocol
counts that as a miss.

## F37 — direct branches, grouping nodes and atomic states are three levels (thesis Decision 2)

**Defect.** One number, `allStates().size()`, stood for "states". It was the
flattened node list, in which a permitted enum counted once as a state and again
per constant: `valueforms.Signal` read as 6 states for a 4-member `permits`
clause with 5 leaves. Nested sealed members were mixed into the same count. A
`non-sealed` branch was silently treated as closed. A type permitted by two sealed
branches appeared twice in the tree, was reported as "two states share the id",
and was declared twice in SCXML.

**Fix.** `State` gains `origin()` (`TYPE` / `ENUM_CONSTANT`), `isAtomic()`,
`isGrouping()` and `isOpenBranch()`. `StateMachine` and `Candidate` gain
`directBranches()`, `atomicStates()` (leaves, each counted once) and
`compositeNodes()`. `StateExtractor.extract(root, model)` also returns
`openBranches` (each `non-sealed` node with the subclasses the model contains) and
`overlaps` (types reachable under ≥2 direct branches). The analyzer warns on both.
`duplicateStateIds()` counts distinct types only. The serializers declare an
overlapping type once and comment its other placement, so SCXML stays valid. The
summary table has `BRANCH` and `ATOMIC` columns, the INFO lines name all three
levels, and the SCXML header count names its level wherever grouping nodes exist.

**Decided and documented:** the exported machine keeps the full hierarchical
expansion, because a successor may name a node at any depth. The direct-branch
count never absorbs it (`SCOPE.md` §2).

**Fixture** `src/test/resources/statelevels`: the decision's own `Phase`/`Speed`
example, a nested sealed branch (`Mode`/`On`), an open branch with a subclass
(`Hatch`/`Ajar`/`WideAjar`), and an overlap (`Signal`/`Red`/`Amber`/`Blink`). Test
`StateLevelsTest`.

**Corpus.** DOT byte-identical. SCXML changed only in the header comment of the
six machines with grouping nodes (`valueforms.Signal`, `namecollision.Link`, and
the four `enumbodies` machines). Summary tables changed format everywhere.

## F38 — a negated event test over a closed Σ is one transition per remaining input

**Defect.** `examples/door` writes `case Closed c -> (event instanceof Lock) ?
new Locked() : new Open()` with `Event permits Push, Lock, Unlock`. The tool
published `Closed --[event instanceof Lock]--> Locked` and `Closed --[!(event
instanceof Lock)]--> Open`: both edges eventless, the input kept as guard text.
The evaluation's transition identity is `(source, event, target)` (Decision 3),
so a labeller writes `Closed --Lock--> Locked`, `Closed --Push--> Open` and
`Closed --Unlock--> Open`, and none of the three matched. The event test had
been split into a label only on the carrier walk and on chain links, and nowhere
was the NEGATED side ever expanded: the else-branch of an event test, the code
after `if (event == X) return ...;`, and the `default` arm of a switch over the
event all stayed eventless (`otherwise`), although over a closed Σ each fires on
a finite, compiler-checked set of inputs.

**Rule.** One routine, asked wherever a condition branches: `eventTest` reads a
condition as `(taken, residual)` over Σ — pure tests (`instanceof`, `==`, `!=`,
`!`, `&&`, `||`) as set operations, a conjunction with a data condition as the
event part plus the data part as guard text — and `thenPaths`/`elsePaths` turn
it into one `(event, guard)` path per input. The else-branch is every input the
test did not take, unconditionally, plus every input it took under
`!residual`. A `default` arm is Σ minus the inputs unguarded arms claim, each
remaining input under the negated guards of the guarded arms naming it
(`defaultArmPaths`). An input already fixed on the path is evaluated, not
re-tested. Never a `!Lock` label: a label is a Σ symbol.

**Not expanded, deliberately**, each keeping the pre-F38 reading: an OPEN event
type (no Σ); two or more closed event parameters (`eventSigma` is empty — "not
TURN" says nothing about the other input); a test in a shape not decided
exactly; an arm that never tests the input (eventless = whatever the input). A
fall-through after an event-testing `if` whose body does not always leave is
reached by every input and stays an eventless `otherwise` edge.

**Soundness detail found while measuring.** Paths are walked one input at a
time, so a branch dead under one input (`if (cmd instanceof Reset)` walked for
`Arm`) is live under another. The first version marked it unreachable per path,
which (a) reported it in the new dead-branch diagnostic on tcp, Capstan,
valueforms and Latch where no branch is dead, and (b) counted its returns as
accounted for F18 while another path still needed them — a way to hide a real
unread return. Deadness is now decided when a SCOPE closes (`SigmaScope`, per
site and per fold, exactly like F18's accounting): candidates minus every node
any path walked. After that, the only dead branch in the corpus is the fixture's.

**Fixtures.** `examples/eventsplit` (`Latch`, `Valve`, controls `Pump` open Σ and
`Gate` two inputs, `Bell` dead branch). `examples/factory` gained `Kick`: with
`Event permits Turn` the delegate `event instanceof Turn ? new Shut() : current`
took its `: current` branch only on a null event, so the self-loop the fixture
documents had no input. Test `EventPartitionTest`; 18 existing assertions were
re-baselined one by one to the fact they now state (e.g. throwguards'
"negated rejection guard" is now "the producer fires on exactly the inputs the
rejection did not take").

**Corpus** (transitions, resolved), machines whose code tests its input:

| machine | before | after |
|---|---|---|
| door.Door | 5, 5/5 | 7, 7/7 |
| tcp.TcpState | 44, 44/44 | 90, 90/90 |
| guardforms.Signal | 22, 22/22 | 41, 41/41 |
| plumbing.Conveyor | 6, 6/6 | 12, 12/12 |
| throwguards.Valve | 6, 6/6 | 10, 10/10 |
| hiddenreturns.Pump | 9, 8/9 | 14, 13/14 |
| valueforms.Signal | 10, 9/10 | 16, 13/16 |
| eventalphabet.Player | 9, 9/9 | 12, 12/12 |
| chaindispatch.Shutter | 5, 5/5 | 7, 7/7 |
| statefuldriver.Sash / Hatch | 6, 6/6 each | 9, 9/9 each |
| retrystate.Poll | 7, 7/7 | 9, 9/9 |
| throwcarrier.Capstan / Hoist | 5, 5/5 each | 6, 6/6 / 7, 7/7 |

Labels-only changes (same count): `chaindispatch.Relay`, `staticfactory.Gauge`,
`turnstile.Turnstile`, `factory.Bolt`. Every added edge splits an edge that
existed; no resolved edge became unresolved; valueforms' extra unresolved edges
are its one unresolved fall-through split per input. Byte-identical:
`lcp_automation` (113/113), `lcp_automation_chatgpt` (111/111), both http2
machines, and every nondeterminism and exhaustiveness warning. The F9 diagnostic
text now says "(arm, input) cell(s)", which is what it counted.
