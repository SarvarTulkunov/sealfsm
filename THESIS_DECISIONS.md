# Thesis decisions of 24 September 2026: what this branch implements

_Companion to the decision record "SealFSM: thesis scope and validation
decisions" (reviewed at commit `0e396bb`). That record stated follow-up tasks and
said explicitly that nothing had yet been implemented. This file maps each
decision to what branch `thesis-decisions` changes, what it deliberately does
not change, and what remains open. The design notes are `FIXLOG.md` F36 and F37;
the thesis-facing statement is `SCOPE.md`._

---

## Decision 1: pattern-scoped machine recognition

| requirement | where |
|---|---|
| Locating a sealed declaration is separate from deciding it is a machine; a rejected type is never counted as a machine or as recovered states | `ExtractionResult.outcomes()` records every examined root as `MACHINE`, `CANDIDATE`, `ABSTAINED`, `VETOED` or `CONVERTED`. States are counted only on machines (`Main` table, `--json`) |
| A candidate only when a supported dispatch pattern gives plausible evidence and the relation cannot be established | `Analyzer.recordCandidate`. The channel opens on a discrimination, a Σ-major commit, or (F36) value-returning sites that amount to a dispatch (`Installation.Report.plausibleDispatch`: a discrimination, or per-state sites fixing ≥2 source states) |
| A candidate is an uncertain classification, not a detected FSM | `Candidate.isProvisional()`, `Candidate.basis()` (`COMMIT_UNPROVEN`, `SOURCE_UNATTRIBUTED`, `INSTALLATION_UNSHOWN`). The summary header says "PROVISIONAL … not recovered FSM states". JSON keys are `provisionalAtomicMembers` and `provisionalDirectBranches`, never `atomicStates` |
| Replace unconditional claims ("reports the state set for every hierarchy it examines") | `SCOPE.md` rewritten around the decision's thesis wording; `README.md` core idea and outcomes; javadoc of `State`, `Candidate`, `StateExtractor`, `CommitEvidence` |
| Evaluation: coverage over all labelled FSMs as well as conditional accuracy | `evaluation/PROTOCOL.md`, `scripts/evaluation/score.py` (`coverage`, `transition_recall_overall` beside `…_conditional`) |

## Decision 2: direct permitted branches and enum constants

| requirement | where |
|---|---|
| Direct branches, grouping (composite) nodes and atomic states as distinct concepts | `StateMachine.directBranches()`, `atomicStates()`, `compositeNodes()` (and the same on `Candidate`); `State.isAtomic()`, `isGrouping()`, `origin()` (`TYPE` / `ENUM_CONSTANT`) |
| An enum is a grouping node; its constants are atomic; it is not counted twice | `State.atomic()` counts leaves only; `examples`-level check: `valueforms.Signal` is 4 direct / 5 atomic / 1 grouping, where the old single count said 6 |
| Nested sealed branches: decide and document whether the export contains deeper nodes | **Decided: yes.** DOT and SCXML keep the full expansion (a successor may name a node at any depth), and the direct-branch count never absorbs it. Documented in `SCOPE.md` §2 and `StateMachine` javadoc. The SCXML header's count names its level wherever grouping nodes exist |
| Metrics use the intended set | Summary table columns `BRANCH` and `ATOMIC`; the analyzer's INFO lines say "N direct branch(es), M atomic state(s) (K grouping node(s))"; `--json` reports all three |
| A `non-sealed` branch is one branch; its descendants are not direct branches | `State.isOpenBranch()`; `StateExtractor.Result.openBranches()` names the subclasses in the model; WARN diagnostic |
| Overlapping direct branches: do not guess a unique state identity | `StateExtractor.Result.overlaps()`; WARN diagnostic; the atomic set counts the type once; `duplicateStateIds()` no longer misreports it as a collision; DOT and SCXML declare it once and comment the other placement |
| Missing permitted declarations | Already reported (F23); unchanged |

Fixture: `src/test/resources/statelevels` (the decision's own `Phase`/`Speed`
example, a nested sealed branch, an open branch with a subclass, an overlap).
Tests: `StateLevelsTest`.

## Decision 3: independent validation on repositories

| requirement | where |
|---|---|
| Record URL, exact commit, module, source roots, Java version, command, classpath, missing-source diagnostics | `evaluation/templates/manifest.template.json` → `evaluation/corpus/<id>.json` |
| Label before inspecting output: FSM / NON_FSM / UNCERTAIN, branches, enum children, transitions, evidence | `evaluation/templates/labels.template.json` → `evaluation/labels/<id>.json`, with `labelledBeforeToolOutput` |
| Negatives and a held-out set | `PROTOCOL.md` step 3; manifests carry `split` |
| TP/FP/FN, abstentions, coverage; state accuracy; transition P/R with unresolved separate; overall vs conditional | `scripts/evaluation/score.py` |
| Stratify by coding pattern and source completeness; rewrites and controlled changes as complementary evidence | `score.py` (`by_pattern`, `by_completeness`); `PROTOCOL.md` step 5 |
| Machine-readable tool output | `--json` → `sealfsm-result.json` (`JsonResultSerializer`, schema 1) |

**Not done, deliberately: no ground-truth labels for real repositories.** The
protocol requires the labels to be made before the tool's output is seen, by the
researcher. Labels written by the same process that changed the tool would not
be independent. `scripts/evaluation/selftest/` scores two of the repository's
own fixtures to check the scorer's mechanics, and says plainly that it is not
evidence. `corpus/kafka` (untracked, no `.git`) has no recorded commit, so it
cannot yet be a manifest entry.

## Decision 4: converters are not FSMs

| requirement | where |
|---|---|
| A hierarchy used only for conversion is not an FSM, and its members are not published on either channel | `Rejection.CONVERTED` / `Outcome.CONVERTED`: every value-returning producer has callers, and every caller uses the result as data. It releases no candidate and still re-offers nested roots |
| Where the source cannot distinguish conversion from a state update, abstain | No caller in the source set (`NO_CALLER`), or a value handed out of sight (`OPAQUE`): not a machine; a provisional candidate with `basis = INSTALLATION_UNSHOWN` if the sites amount to a dispatch, else a plain abstention |
| Evidence: a caller installing the result (`current = next(current)`, a state field, a verified context mutator) | `detect/dispatch/Installation`: four sinks (root-typed field write, write-back into the state variable, recognised mutator, run-to-completion driver's selector), up to 6 call boundaries (`-Dsealfsm.maxInstallationDepth`) |
| Missing caller evidence is uncertainty, not proof of conversion | `NO_CALLER` is never `CONVERTED` |
| The same commit rule across return-based patterns, switches and typed handlers included | Applied to every site whose successor leaves by `return`: per-state overrides, centralized switches and chains, typed handlers, functional callables, carriers, returned accumulators. F35's family threshold is retired as an acceptance rule and kept only as the candidate channel's plausibility test |
| `Length` must cease to appear as a machine | It is a provisional candidate: two uncalled converters, which is missing caller evidence |
| `Lamp` recoverable when its successor becomes the current state | `typedhandler.LampPanel` stores it back: a machine, 1/1, `VIA_CALLER` |
| Change tests pinning the `Length` false positive; controls for converter use, demonstrated state update, missing callers | `ExtractionIntegrationTest.everyTypedHandlerIsASiteAndInstallationDecides` replaces the pin; `InstallationEvidenceTest`; `examples/converters` (`Tint` / `Shade` / `Hue` / `Currency`) and `typedhandler.Temperature` |
| **Remaining design choice**: the representation of uncertain hierarchies | **Taken and documented** (`evaluation/PROTOCOL.md`, "The four classification outcomes"). An uncertain hierarchy is a provisional candidate with `INSTALLATION_UNSHOWN` when its sites amount to a dispatch, and otherwise a plain abstention with a diagnostic naming the missing evidence. An established conversion is never a candidate |

### What the decision cost, measured

- 22 corpus machines had no installing caller, because their fixtures were
  written without a driver (the `@Fsm`-marked `Vend` is not counted, since a
  marker needs no evidence). 19 of them, in 14 fixture directories, received a
  minimal, unseeded store-back class, and so did the value-returning half of
  `plumbing`. Every `.dot` and `.scxml` of those fixtures is
  byte-identical to `master`, which is the check that the drivers added
  evidence and nothing else.
- The other three changed verdict as the decision intends. `typedhandler.Length`
  (the named example), `typedhandler.OrderState` (the pipeline without its
  driver) and `examples/cancellation` (installs through `AtomicReference`, L2)
  are now provisional candidates. F7's walk stays covered by the new twin
  `examples/functionaldriver`.
- The test-resource fixtures (`bindingframes`, `foldbinding`, `carrierdispatch`,
  `carrierreject`, `callambiguity`, `unreadmember`) received store-back
  installers for the same reason.
- `nestedroots.Rebuilder` (`branch = branch.replaceChild(child)`) keeps the
  compositional-veto control meaningful. A persistent-tree update is a store-back
  by F36's rule, and only the veto keeps it from being published.
- Value-returning machines now report commit evidence `VIA_CALLER` rather than
  `DIRECT` (summary table, `--json`). The `.dot`/`.scxml` evidence comment stays
  reserved for the probe's `VIA_CALLEE`, which qualifies edges rather than the
  classification.

### Advisory notes (where the implementation had to interpret)

1. **What counts as "a state field".** A field declared with the hierarchy
   root, which is the rule the k = 1 probe already uses. A converted value cached
   in a root-typed field therefore reads as installed. That follows the
   decision's clause "an assignment to a state field", and it is documented as
   a residual. The stricter alternative (the field must also be read as a state
   input) was not taken, because every corpus driver satisfies both and the
   stricter rule has no fixture to justify it yet.
2. **Local flow is flow-insensitive.** Every read of a local the value was
   assigned to is asked. That errs toward installation. It is documented in
   `Installation`.
3. **Library containers stay opaque (L2).** `AtomicReference.set` and similar
   calls are not given a meaning. Doing so is a modelling decision the thesis
   has so far declined.
4. **Recall cost on real code.** A library whose transition function is public
   API and whose driver lives in client code now abstains. The evaluation
   protocol counts it as a missed machine, as Decision 3 requires. Measure how
   often it happens on the held-out set before drawing conclusions from it.
5. **`examples/cancellation` does not compile** with `javac`, and did not on
   `master` either (`accumulateAndGet` takes `(V, BinaryOperator<V>)`). The
   branch leaves it as it was, so the fixture still matches the shape it was
   taken from; only its javadoc changed.
