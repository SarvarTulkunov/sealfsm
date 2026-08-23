# SealFSM corpus report — 2026-08-23

Source of every figure in this report: `reports/2026-08-23/results.json`. No other file
was consulted for numeric content; `findings.md`, `summary.txt` and `run.log` are quoted
only for their prose fields. Per CORPUS_PROTOCOL.md §11, a figure absent from
`results.json` does not appear below.

---

## 1. Run metadata

- **Timestamp (UTC):** 2026-08-23T11:44:05Z
- **Tool commit:** `2d397ca5438d06b9954f7cdfacc0e7a3e74da292` (short `2d397ca`)
- **Working tree:** dirty. `results.json` records `git_status_porcelain` as non-empty,
  listing a deletion (`examples/lcp_automation_chatgpt/LcpDemo.java`), sixteen renames of
  `examples/lcp_automation_chatgpt/*.java` into `examples/lcp_automation_chatgpt/java/*.java`,
  and untracked paths: `.claude/`, `CORPUS_PROTOCOL.md`, `FIXLOG.md`,
  `examples/lcp_automation_chatgpt/PROVENANCE.md`, `examples/lcp_automation_chatgpt/meta.json`,
  `examples/lcp_automation_chatgpt/oracle/`. `results.json` records this status as captured
  against the tree as it stood before this run's own additions of
  `reports/2026-08-23/*` and `src/test/java/io/sealfsm/corpus/LcpAutomationChatgptTest.java`,
  and states the run was executed against this dirty tree per explicit instruction, not
  cleaned first.
- **JDK version:** 21.0.8 (2025-07-15 LTS), Oracle Corporation, HotSpot 64-Bit Server VM
- **Maven version:** Apache Maven 3.9.15
- **Scope:** `dev`. `holdout_unsealed`: false. The invoking instruction did not contain
  the literal token `UNSEAL-HOLDOUT`; the holdout split was not compiled, run, or scored.

### Corpus composition

`results.json` records the corpus note verbatim: counts are for protocol-conformant
corpus examples only (`meta.json` + `PROVENANCE.md` + `oracle/` present, per
CORPUS_PROTOCOL.md §2). Directories under `examples/` carrying `.java` at the top level
with no `meta.json` are pre-protocol development fixtures, excluded from every count
below, listed separately, not scored.

- **Total protocol-conformant examples: N = 1.**
- **By split:** dev = 1, holdout = 0.
- **By idiom:** CENTRALIZED_DISPATCH = 1.
- **By commit_shape:** argument-passed = 1.
- **By expected_verdict:** fsm = 1.
- **By oracle_provenance tier:** external-spec = 1.

**Pre-protocol fixtures excluded: count = 29.** Not compiled, not run, not scored in
this report. Ids: accumulator, barefield, cancellation, dhcp-client-chatgpt,
dhcp-client-claude, door, errorhandling, eventalphabet, factory, ffmpeg, foreignfold,
gofcontext, guardforms, http2-stream-claude, http2-stream-gemini, lcp_automation,
localvar, namecollision, nondeterministic, nonreturning, plumbing, plumbing-mutation,
shape, tcp, traffic, treebuilder, turnstile, valueforms, websocket-claude. A
filesystem check at report time confirmed exactly one `meta.json` exists under
`examples/` (`examples/lcp_automation_chatgpt/meta.json`), consistent with this count.

### Crashes, timeouts, empty output

Of the 1 scored example: 0 crashed, 0 timed out, 1 (`lcp_automation_chatgpt`) produced
empty output — `machines_emitted: 0`, `dot_emitted: false`, `scxml_emitted: false`.
`results.json` records `crashed: false` for this example and notes that exit code 1 is
the tool's own "no machines found" path, not an uncaught exception; stderr carried only
SLF4J provider warnings.

---

## 2. Aggregate

N = 1 for every figure in this section. CORPUS_PROTOCOL.md §13 requires omitting the
percentage for any cell with N < 20; no percentage is given anywhere below.

### States (set equality, no partial credit)

| exact matches | N |
|---|---|
| 0 | 1 |

### Transitions

| TP | FP | FN | dropped_without_marker |
|---|---|---|---|
| 0 | 0 | 120 | 120 |

Precision, recall and F1: all `null` in `results.json`. `results.json` states this
explicitly and does not compute a substitute value.

**Unresolved edges (abstentions), on their own line, excluded from the precision
denominator:** `unresolved_total = 0` across the corpus this run. `results.json` notes
the exclusion of abstentions from precision is vacuous at this count, not omitted, since
there were no abstentions to exclude. `abstentions_excluded_from_precision: 0`.

`dropped_without_marker = 120`: all 120 oracle transitions for the single example were
neither extracted nor covered by any unresolved edge. `results.json` notes this figure
coincides numerically with FN only because `unresolved_total = 0` this run; the two are
recorded as separate fields per CORPUS_PROTOCOL.md §10.

### Negative class

| N | correctly rejected | false positives |
|---|---|---|
| 0 | 0 | 0 |

`results.json` records this as a coverage gap: no `not_fsm` example exists in the
protocol-conformant dev corpus at this run (n=1, `expected_verdict=fsm`). The
CORPUS_PROTOCOL.md §8 negative quota (at least one in four) was unmet at the corpus
level. This is recorded as a coverage gap, not computed as a rate.

---

## 3. Stratification

CORPUS_PROTOCOL.md §13 requires recall stratified per `idiom` and per `commit_shape`.
`results.json`'s aggregate note states explicitly that per-idiom and per-commit-shape
stratified recall and precision/recall/F1 are not computed at n=1: a single-example
corpus produces a number that reads as a stratified result but is not one. Only the raw
counts already given in §2 are reported; no per-idiom or per-commit-shape table is
constructed from a corpus of one cell.

For reference, the single example's classification: idiom = CENTRALIZED_DISPATCH,
commit_shape = argument-passed. Both strata therefore hold N = 1, the same single
outcome (transitions: 0 TP / 0 FP / 120 FN) already reported in §2, and are not restated
as a separate table.

---

## 4. Per-example table

| id | split | idiom | commit_shape | expected verdict | verdict correct | states exact | transition TP/FP/FN | unresolved | event_matching | finding ids |
|---|---|---|---|---|---|---|---|---|---|---|
| lcp_automation_chatgpt | dev | CENTRALIZED_DISPATCH | argument-passed | fsm | false | false | 0/0/120 | 0 | degraded | centralized-carrier-nondetection |

Additional per-example fields recorded in `results.json`: `machines_emitted = 0`,
`exit_code = 1`, `crashed = false`, `timed_out = false`, `wall_time_ms = 1922`,
`compiled = true`, `dropped_without_marker = 120`, `events_extracted = false`. States
missing: Initial, Starting, Closed, Stopped, Closing, Stopping, ReqSent, AckRcvd,
AckSent, Opened (10 of 10 expected). States extra: none. `link_status: ok`; `results.json`
records a link revalidation against `https://www.rfc-editor.org/rfc/rfc1661.txt` (HTTP
200) and its archive URL (HTTP 200) at `2026-08-23T11:41:00Z`. `source_permalink` is
null with the note that this is a synthesized example (`synthesis_basis`: RFC 1661
section 4.1), not a harvested one, so there is no permalink to revalidate.

`event_matching = "degraded"` carries the note that no machine was emitted, so the event
alphabet was never enumerated; in this example the degradation is total, since no
(source, target) pairs were scored either, `tool_n = 0`.

---

## 5. Deltas

`results.json`'s `regression_vs` object records: `previous_run: null`,
`previous_tool_commit: null`. Its note states this is the first run under
CORPUS_PROTOCOL.md, that no previous dated report exists under `reports/`, and that
there is no baseline to diff against. `regressed`, `fixed`, and `unchanged_failing` are
all empty arrays, recorded as empty by definition and not as a result. A directory
listing of `reports/` at report time found only `reports/2026-08-23/`, confirming no
prior dated run exists to diff against. No commit SHAs are named for a previous run
because none exists; this section reports that absence rather than a comparison.

---

## 6. Notable cases

### Soundness findings (fabricated edges, wrong state sets) — reported first

One finding is recorded in `findings.md`, of soundness class `wrong-state-set`:

**`centralized-carrier-nondetection`** (F-code: NEW; examples: `[lcp_automation_chatgpt]`;
idiom: CENTRALIZED_DISPATCH; commit_shape: argument-passed)

- **Symptom** (as recorded in `findings.md`): SealFSM reported zero state machines for a
  sealed hierarchy whose centralized dispatch switch commits its result as a constructor
  argument to a non-hierarchy carrier record, rather than as a direct return/field/local
  value of the hierarchy type.
- **Expected** (oracle): 10 states (Initial, Starting, Closed, Stopped, Closing,
  Stopping, ReqSent, AckRcvd, AckSent, Opened) and 120 transitions, e.g.
  `Initial Up Closed` and `Closed RCR+ Closed` (`oracle/transitions.tsv`).
- **Actual** (tool output): 0 machines emitted, 0 states, 0 transitions. Sole
  diagnostic: `[INFO] lcpchatgpt.LcpState: skipped - no transition producer found (may
  be event/? type or unresolved dispatch)`. Exit code 1 (the tool's "no machines found"
  path, not a crash). No `.dot`, no `.scxml` written.
- **Node-shape** (as recorded in `findings.md`, abstract, no identifiers beyond
  category names): a sealed interface H with ten permitted record subtypes. A method M,
  declared on a class outside H, contains a `CtSwitchExpression` whose selector's static
  type is H, but whose immediate syntactic parent is a `CtReturn` where the enclosing
  method's return type is a carrier record type C, C != H, one of whose record
  components is of type H. Each arm of that switch delegates (a plain `CtInvocation`,
  not inlined) to a second per-state helper method that itself contains an inner
  `CtSwitchExpression` selecting on an unrelated enum type (the event alphabet), whose
  arms construct C via `new C(new ConcreteH(), otherArgs)` — an H-typed
  `CtConstructorCall` passed as a direct constructor argument to C, which is itself the
  yielded/returned value of the inner switch. `findings.md` records that no switch
  anywhere in the model has both a selector typed H and a commit (return/field-write/
  local-declaration) typed H directly, so `DispatchCommitDetector`'s `commitFormOf(...)`
  returned null for the outer switch ("foreign codomain — an exhaustive fold") and never
  inspected the inner ones (their selector type is the event enum, not H, so
  `dispatchesOnHierarchy` already rejects them). `findings.md` records that
  `CarrierTransitionDetector` did not apply either: its POLY_CARRIER recognition
  requires the carrier-returning method to be declared on a permitted subtype
  (POLYMORPHIC dispatch); here the carrier-returning methods are all declared outside H
  (CENTRALIZED_DISPATCH). `findings.md` states the commit-form axis (VALUE_RETURN /
  FIELD_MUTATION / LOCAL_ACCUMULATOR / POLY_CARRIER) has no member for "H committed one
  constructor-argument level below a centralized dispatch's own codomain match point" —
  recorded as a fifth combination of {encoding} x {commit} outside the four the
  commit-form fixture family covers.
- **Diagnostic:** emitted (`[INFO] lcpchatgpt.LcpState: skipped - no transition producer
  found (may be event/? type or unresolved dispatch)`), per `findings.md`.

No fabricated edges (FP) were recorded this run: `results.json` reports FP = 0 across
the corpus.

### Dropped edges

`dropped_without_marker = 120` for `lcp_automation_chatgpt`: `results.json` records that
all 120 oracle transitions were neither extracted nor covered by any unresolved edge,
because zero machines were emitted.
