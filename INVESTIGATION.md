# INVESTIGATION — decoupling state enumeration from transition resolution

Step 0 of `PROMPT_decouple_states_from_transitions.md`. Written as the
investigation ran; the FIXLOG entry and the thesis scope paragraph are both
derived from it.

Baseline: `master` at `da4f5cb` ("F25: the fold discarded the caller's argument
bindings"), Spoon 10.4.2, `mvn package -DskipTests` green.

---

## 0.1 Probe inputs

Three throwaway fixtures under `src/test/resources/probe/`, in three distinct
Java packages. Each is the *same* three-state sealed hierarchy
(`Latch permits Idle, Armed, Fired`) with the *same* exhaustive centralized
switch over the state, differing only in what the arms hand off to.

| probe | arm | callee |
|---|---|---|
| `probe/returnh` | `case Idle i -> pick(i, signal);` (switch **expression**, `return`ed) | `Latch pick(Latch, Signal)` — returns from inside a `synchronized` block, which the walker does not descend |
| `probe/voidh` | `case Idle i -> advance(i, signal);` (switch **statement**) | `void advance(Latch, Signal)` — body is `this.state = table.get(...)`, an H-typed field write |
| `probe/voidforeign` | `case Idle i -> advance(i, signal);` (switch **statement**) | `void advance(Latch, Signal)` — body is `this.label = ...`, a **String** field write |

`voidh` and `voidforeign` are indistinguishable at the call site (same hierarchy,
same method name, same arity, same arguments); the only difference is the
declared type of the field the callee writes.

All three compile under `javac`.

## 0.2 Instrument output (verbatim, SLF4J notice elided)

### `probe/returnh` — `--explain`

```
Found 1 state machine(s); wrote 2 file(s) to out\probe\returnh
MACHINE                DISPATCH             STATES  TRANS  RESOLVED  COMMIT                       SUCCESSOR FORMS
Latch                  CENTRALIZED_DISPATCH      3      3       0/3  VALUE_RETURN                 -
Diagnostics:
  [WARN] probe.returnh.Latch: 3 return(s) of an in-model helper could not be reached by the walk (a construct outside its modelled subset encloses them); each is recorded as an unresolved transition, never dropped
  [WARN] probe.returnh.Latch: initial state could not be determined
  [INFO] probe.returnh.Latch: extracted - 2 centralized transition function(s), committing via VALUE_RETURN; 3 states, 0/3 transitions resolved
```

No `--explain` block is printed: only rejections are traced, and this root was
accepted. `Latch.dot`:

```
  "Idle";  "Armed";  "Fired";
  "Idle"  -> "?" [style=dashed, color="#b00020", label="unresolved"];
  "Armed" -> "?" [style=dashed, color="#b00020", label="unresolved"];
  "Fired" -> "?" [style=dashed, color="#b00020", label="unresolved"];
```

### `probe/voidh` — `--explain`

```
Found 0 state machine(s); wrote 0 file(s) to out\probe\voidh

  probe.voidh.Latch
      - marker annotation (@Fsm/@FSM/@StateMachine): absent - continuing structurally
      - compositional veto: not fired - no production nests a hierarchy value inside another
      - per-state transition method (declared on a member, returns the hierarchy type): FAILED - none found
      - centralized transition function (produces a hierarchy value AND discriminates one): FAILED - none found
      - functional transition callable (lambda / anonymous-class method with that signature): FAILED - none found
      - dispatch with a hierarchy-typed commit (a switch or instanceof chain over the hierarchy whose result is installed as a hierarchy value): FAILED - none found
      - carrier-based per-state transition (successor handed to a non-hierarchy wrapper): FAILED - no per-state method returns a wrapper carrying a hierarchy value
      - no predicate above produced a transition producer: ABSTAINING. Not a verdict about the hierarchy - it may be the event alphabet, or its dispatch may sit somewhere no recognizer can see
Diagnostics:
  [INFO] probe.voidh.Latch: skipped - no transition producer found (may be event/Sigma type or unresolved dispatch)
```

### `probe/voidforeign` — `--explain`

Line-for-line identical to `probe/voidh`'s trace and diagnostic, modulo the
package name. Every predicate reports the same verdict, including the last one.

### `DebugHarness`

`probe/returnh` reaches STAGE 6 with `states=3 transitions=3 resolved=0
unresolved=3`, and STAGE 3 reports:

```
  Latch                      isFSM=true  encoding=CENTRALIZED_DISPATCH
                             reason: 2 centralized transition function(s), committing via VALUE_RETURN
    Centralized methods:
      LatchMachine.next(Latch, Signal) -> Latch
      LatchMachine.pick(Latch, Signal) -> Latch
```

`probe/voidh` and `probe/voidforeign` both stop at STAGE 3:

```
  Latch                      isFSM=false  encoding=MIXED
                             reason: no transition producer found (may be event/Sigma type or unresolved dispatch)
    SKIPPED (not a state machine)
```

STAGE 4 (extract states) is never reached for either.

### `DebugAst --returns`

- `probe/returnh`: `next` returns a `CtSwitchExpressionImpl` typed
  `probe.returnh.Latch`; `pick` returns a `CtInvocationImpl` typed
  `probe.returnh.Latch`.
- `probe/voidh` and `probe/voidforeign`: `LatchMachine` reports
  `method: void advance(Latch current, Signal signal)` and
  `method: void step(Signal signal)` with **no return listed for either** — there
  is no returned expression anywhere in the driver, which is precisely why the
  commit is invisible from the call site.

---

## 0.3 The five questions

### q1 — Where is the coupling?

`Analyzer.analyze`, `src/main/java/io/sealfsm/Analyzer.java:103-109`:

```java
103            if (!c.isStateMachine()) {
104                result.info(root.getQualifiedName(),
105                        "skipped — " + c.reason() + reoffer(root, c, pending, claimed));
106                reportResolution(root, result, resolution, true);
107                if (trace != null) result.explain(root.getQualifiedName(), trace);
108                continue;
109            }
```

The `continue` at :108 is the coupling, and it is unconditional. `StateExtractor`
is invoked exactly once in the whole file, at
`src/main/java/io/sealfsm/Analyzer.java:121`:

```java
121            StateExtractor.Result states = stateExtractor.extract(root);
```

which is **after** the `continue`. So states are **never extracted** for a
rejected root — they are not extracted and then discarded. `DebugHarness`
confirms this independently: STAGE 4 is not reached.

The classification that reaches :103 is decided in
`StateMachineClassifier.classify`,
`src/main/java/io/sealfsm/detect/StateMachineClassifier.java:153-194` — a chain
of `hasDist`/`hasCentral` disjuncts over `DispatchFinder.Sites`, falling through
to `Classification.no(...)` at :194. Every one of those disjuncts is a
*transition-producer* predicate. Nothing on the path consults the `permits`
clause.

**The premise of the task is confirmed**: claim 1 (states exact) is gated on
claim 2 (a transition producer was recognised), and the gate is one `continue`.

### q2 — What happens on `probe/returnh`?

**It is ACCEPTED, and the commit is recognised.** Not by
`CommitClassifier.classify` reading the arm's `CtInvocation`, but one level up,
which is the important detail:

- `CommitClassifier.classify` is asked about the **switch**, not about an arm
  (`src/main/java/io/sealfsm/detect/dispatch/CommitClassifier.java:59`). Its
  parent is a `CtReturn` (:65), so the rule that fires is (a): the *host method's*
  codomain decides (:66-70). `LatchMachine.next` returns `Latch`, which is in H,
  so the commit is `VALUE_RETURN`. What the arms produce is never inspected for
  the commit question.
- Independently, `StateMachineClassifier.findCentralizedTransitionMethods`
  (:397-433) admits both `next` and `pick` on their signature (returns H, takes an
  H-typed parameter), so `sites.centralized()` is non-empty and `hasCentral` is
  true at :161 regardless.

Verdict printed by `--explain`: none — the root is accepted, and only rejections
are traced. The classification reason is
`2 centralized transition function(s), committing via VALUE_RETURN`.

The tool then reports **3 states, 3 transitions, 0 resolved, one unresolved edge
per dispatched arm with a known `from`** — which is exactly the Tier 2 output
contract the prompt specifies, already satisfied for this shape.

**Branch A does not apply.** There is no recognition bug at this cell: the
codomain proof is already held and already used.

### q3 — What happens on `probe/voidh` and `probe/voidforeign`?

Both abstain. **They are not distinguished at all**, and they abstain for
literally the same stated reason — the two `--explain` traces are line-for-line
identical modulo the package name, and both end:

```
      - dispatch with a hierarchy-typed commit (...): FAILED - none found
      - no predicate above produced a transition producer: ABSTAINING.
```

Why, precisely:

- `findCentralizedTransitionMethods` requires the host to *return* H
  (`StateMachineClassifier.java:414`: `if (ret == null || !hierarchy.contains(ret.getQualifiedName())) continue;`).
  `step` and `advance` are both `void`. No centralized site.
- `DispatchCommitDetector.addIfProducer`
  (`src/main/java/io/sealfsm/detect/DispatchCommitDetector.java:200-209`) finds
  the switch, calls `commitFormOf` → `CommitClassifier.classify`, whose parent
  test (:60-96) sees a `CtCase` parent rather than a return, an assignment or a
  local declaration, so it answers `null`; `mutatorCommitIn` (:451-465) then scans
  the arms for a `MutatorRecognizer.commitOfCall`, and `advance` is not a mutator
  (it has **two** parameters, so `soleRootParameter` declines at
  `MutatorRecognizer.java:139`). Commit `null` → `return` at :205.
- No per-state method returns H, so the carrier path declines too.

So the switch over H is *found* by the locus half and thrown away by the commit
half, and the trace's `FAILED - none found` conflates "no dispatch" with "a
dispatch that commits nothing". See q5.

### q4 — Which existing fixtures currently produce zero machines?

Enumerated by running the CLI over every directory in `examples/` (40 of them),
not from a hard-coded list:

```
foreignfold : 0
shape       : 0
treebuilder : 0
```

All three are documented, intentional negative controls (CLAUDE.md: `foreignfold`
= the exhaustive fold into `String`/`int`; `shape` = the plain sum type;
`treebuilder` = the sibling-vs-nested guard on both acceptance paths).

Per-root, across the whole corpus, the rejections are:

- **event alphabets Σ** (intended, and the reason the abstention wording says so):
  `door.Event`, `tcp.Event`, `turnstile.Event`, `factory.Event`, `localvar.Event`,
  `gofcontext.Event`, `eventalphabet.Event`, `guardforms.Trigger`,
  `lcp.LcpEvent`, `http2.StreamEvent`, `dhcpclaude.DhcpEvent`,
  `websocket.WebSocketEvent`, `ffmpeg…FfmpegEvent`;
- **documented negative controls**: `examples.shape.Shape`,
  `examples.foreignfold.Mode`, `treebuilder.Expr` (VETOED),
  `chaindispatch.Glyph` (codomain control), `chaindispatch.Tree` (composition
  control), `retrystate.Layer` (VETOED), `nestedroots.Node` (VETOED),
  `nestedroots.Contents` (the re-offer control);
- **documented composite parents that abstain and re-offer**: `nestedroots.Message`,
  `nestedroots.Envelope`.

**No unintended loss was found.** Every zero is accounted for by a fixture
Javadoc or by CLAUDE.md. There is therefore no separate finding to record here.

### q5 — Does `--explain` distinguish "no dispatch found" from "dispatch found, commit not proven"?

**No, and this is a defect in the diagnostic channel.** The single line

```
- dispatch with a hierarchy-typed commit (a switch or instanceof chain over the
  hierarchy whose result is installed as a hierarchy value): FAILED - none found
```

is emitted from `StateMachineClassifier.classify:150-152`, whose argument is
`verdict(sites.producers().size())` — and `producers()` is the *composed* answer
(locus ∧ commit ∧ veto: `DispatchFinder.producerSites` →
`DispatchCommitDetector.find`). When the count is zero the reader cannot tell
which conjunct failed. `probe/voidh`, `probe/voidforeign` and a sealed type that
is never switched over at all all print the identical line.

`DispatchFinder.switchSites` (`DispatchFinder.java:175`) already answers the locus
half alone and is not consulted by the trace. Fixing this is in scope: the Tier 3
candidate report *is* the "dispatch found, commit not proven" case, and it cannot
be reported from a channel that cannot express it.

---

## 0.4 Branch

**Branch B — `probe/returnh` is accepted, `probe/voidh` is not: a genuine missing
capability.**

Evidence that selected it:

1. `probe/returnh` yields one machine, 3 states, 0/3 resolved, one unresolved
   edge per arm — so the codomain proof is neither missing nor refused
   (rules out Branch A).
2. `probe/voidh` and `probe/voidforeign` both abstain, with identical traces
   (rules out Branch C — nothing is being over-admitted; the foreign fold behind
   one indirection is rejected, along with the real machine beside it).
3. States are never extracted for a rejected root — `Analyzer.java:108` precedes
   `Analyzer.java:121`, and `DebugHarness` STAGE 4 is not reached — so the
   coupling is exactly where the task says it is (rules out Branch D).

The finding, stated as Step 0 establishes it rather than as it was reported:

> **Two independent defects, of different kinds.** (i) *Structural coupling*: the
> analyzer derives the state set only on the accepted path, so a hierarchy whose
> transition producer is unrecognised reports zero states rather than its exact
> `permits` closure — claim 1 is gated on claim 2 by one `continue`. (ii)
> *Missing capability*: the commit is read only from the dispatch's immediate
> syntactic context, so a dispatch whose arms commit **inside a `void` callee** is
> not a recognised dispatch at all. (i) is not caused by (ii) — closing (ii) alone
> would move `probe/voidh` and leave `probe/voidforeign`, `shape` and every future
> unrecognised idiom reporting zero states. The symptom as reported ("the
> hierarchy is lost, states included") is (i); the shape used to demonstrate it is
> (ii).

There is a third, smaller finding, which is (i)'s diagnostic counterpart: the
`--explain` channel cannot express "dispatch found, commit not proven" (q5), so
Tier 3 has nowhere to be reported from until it can.

## 0.5 Re-scope

Given the above, of "Design to implement":

| item | status | why |
|---|---|---|
| **Tier 1 ACCEPTED** | already correct | unchanged behaviour |
| **Tier 2 DETECTED_EMPTY** — states complete, one unresolved edge per dispatched arm | **already satisfied at the DIRECT cell**; required at the VIA_CALLEE cell | `probe/returnh` already emits `Idle --?--> ?` per arm with a known `from`. What is missing is (a) the *label* — nothing distinguishes Tier 2 from Tier 1 without recomputing it — and (b) the same output for a probe-established commit, where the arm is a bare call statement that F10 correctly ignores, so the walk would emit **nothing** and drop three arms silently |
| **Tier 3 CANDIDATE** + `ExtractionResult.candidates()` | **required, and it is the actual fix for the reported symptom** | this is where the `continue` at `Analyzer.java:108` is decoupled: a rejected root still gets its `permits` closure enumerated |
| **k = 1 commit-existence probe** | **required**, but narrower than the prompt's wording — see below | closes `probe/voidh` without admitting `probe/voidforeign` |
| **`CommitEvidence` IR field** | **required** | DIRECT and VIA_CALLEE are both genuinely reachable, so the two must not pool |
| **`isDetectedEmpty()`** | **required** | Tier 1 and Tier 2 are otherwise indistinguishable to a consumer |
| **`--explain` names the tier and the evidence**, and separates locus from commit | **required** | q5 |
| Branch A's codomain fix in `CommitClassifier` | **not required — do not implement** | q2: the proof is already held and used. Adding a probe behind it would be dead code and a misattributed finding |

**One narrowing of the probe, and it is forced rather than chosen.** The prompt
scopes the probe to "an arm whose produced value is a call, **or** an arm that is
a bare call statement". The first half is inert and must stay inert:

- if the switch's result *is* committed, the commit is already `DIRECT` (that is
  `probe/returnh`) and the probe would be claiming credit for a machine the direct
  path already found — which the prompt's own test 5 forbids;
- if the switch's result is *not* committed, then the caller either discards the
  value (F10 — JLS §14.8 — a discarded value cannot be a committed successor) or
  routes it somewhere outside H (that is `foreignfold`). Opening the callee cannot
  license either: whatever the callee returns, *this* caller does not install it.

So the probe's whole added reach is the second half — an arm, or a chain branch,
whose statement is a bare call — and there the only thing that can prove a commit
is a **side effect** inside the callee: an H-typed field write, or a call to a
recognised mutator. A `return` inside such a callee proves nothing, because the
caller throws the value away. This narrowing is what makes the probe's positive
answer mean the same thing the direct rules' positive answer means.

Two further consequences of the same reasoning, recorded so they are not
re-derived:

- "assign to an H-typed local that is subsequently committed" needs no rule of its
  own. If the local is subsequently committed, the committing act is itself a
  return, a field write or a mutator call in the same body — each already
  detected. Writing the local rule as well would be a second notion of the same
  thing.
- F9 must be asked **before** the body is scanned, not after: a non-void callee
  with no `return` anywhere provably cannot complete normally (JLS §8.4.7), so it
  is an undefined input contributing no evidence and no edge. Asking afterwards
  would let such a callee's incidental field write count as a commit.

---

## Post-implementation measurements

The finding as recorded for the thesis is **F26** in `FIXLOG.md`; the scope
paragraph derived from it is `SCOPE.md`.

### The three probes, after

| probe / fixture | before | after |
|---|---|---|
| `probe/returnh` → `examples/opaquesuccessor` | 1 machine, 3 states, 0/3, `DIRECT` | unchanged in kind: 1 machine, 4 states, 0/4, `DIRECT`, now labelled **Tier 2** |
| `probe/voidh` → `examples/voidcommit` | **0 machines, 0 states** | 1 machine, 4 states, 0/4, `FIELD_MUTATION`, **`VIA_CALLEE`**, Tier 2 |
| `probe/voidforeign` → `examples/voidfold` | 0 machines, 0 states, no reason distinguishable from a plain sum type | 0 machines — **and** 1 candidate carrying all 4 states, the dispatch site named, the reason given |

`src/test/resources/probe/` is deleted; fixtures 1–3 are the promoted probes, so
nothing is written twice.

### Corpus

Over all 40 pre-existing fixture directories, run one directory at a time into its
own output tree, before and after:

* **every `.dot` and `.scxml` byte-identical.** `sample-output/` needs no
  re-baseline.
* totals unchanged: **46 machines, 599 transitions, 591 resolved**.
* **+12 candidates** newly reported on hierarchies that previously produced no
  state set at all — eight event alphabets Σ that are switched over, three
  documented negative controls that genuinely *are* discriminated
  (`foreignfold.Mode`, `chaindispatch.Glyph`, `chaindispatch.Tree`), and
  `nestedroots.Message`.
* `examples/shape`, `treebuilder`, `retrystate.Layer`, `nestedroots.Node`,
  `door.Event` and `tcp.Event` correctly yield **no** candidate.
* with the six new fixtures: 49 machines, 610 transitions, 591 resolved, 15
  candidates. The resolved count is flat by construction — every new machine is
  Tier 2.
* **194 tests green**, the pre-existing 183 unchanged.

### The one regression the corpus caught, and what it changed

The first build of the probe admitted `nestedroots.Message` and, because an
accepted parent claims its members, **lost `nestedroots.Body`** — the real
machine. `BodyContext.setState` writes a `Body`-typed field, and `Body` is inside
H(`Message`), so probing it proved a commit for the containing sum type. The fix
is the clause `MutatorRecognizer.soleRootParameter` already makes for the same
reason: the written field's declared type must be the hierarchy **ROOT**, not
merely a member. A state field must be able to hold every state, so it is declared
with the root; the narrowing costs nothing real. It is now pinned by
`StateCompletenessTest.everyExistingGoldenIsUnchanged`.

### What was NOT implemented, and why

Branch A's codomain fix in `CommitClassifier` — q2 established that the proof is
already held and already used, so adding a probe behind it would have been dead
code and a finding attributed to the wrong place.

Three of the four questions the prompt scoped the probe to are **inert by
construction** at the only shape the probe applies to (a call in statement
position, whose value Java discards): a `return` installs nothing the caller
keeps, an H-typed local dies with the frame, and "a local subsequently committed"
is already seen as the field write that commits it. The fourth — handing a value
to a recognised mutator — requires reading the *mutator's* body, a second hop, and
the probe's depth is exactly 1. That case is a Tier 3 candidate and is recorded as
a scope line in `CLAUDE.md` rather than silently absent.
